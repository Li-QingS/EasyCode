package com.easycode.tui;

import com.easycode.provider.StreamHandler;

import com.easycode.provider.ToolCall;

public final class MarkdownRenderer implements StreamHandler {

    private static final String BOLD_ON = "\033[1m";
    private static final String BOLD_OFF = "\033[22m";

    private final StreamHandler delegate;
    private boolean pendingStar;
    private boolean bold;
    private boolean atLineStart = true;
    private int headingHashes;

    public MarkdownRenderer(StreamHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onToken(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);

            // 行首 # 检测（支持 # ## ###）
            if (atLineStart && c == '#') {
                headingHashes++;
                continue;
            }
            if (headingHashes > 0 && c == ' ') {
                headingHashes = 0;
                delegate.onToken(BOLD_ON);
                bold = true;
                continue;
            }
            if (headingHashes > 0 && c != '#') {
                for (int j = 0; j < headingHashes; j++) delegate.onToken("#");
                headingHashes = 0;
            }

            atLineStart = false;

            // ** 粗体检测
            if (c == '*') {
                if (pendingStar) {
                    pendingStar = false;
                    delegate.onToken(bold ? BOLD_OFF : BOLD_ON);
                    bold = !bold;
                } else {
                    pendingStar = true;
                }
            } else {
                if (pendingStar) { delegate.onToken("*"); pendingStar = false; }
                delegate.onToken(String.valueOf(c));
            }

            if (c == '\n') {
                atLineStart = true;
                headingHashes = 0;
            }
        }
    }

    @Override public void onComplete() {
        if (pendingStar) { delegate.onToken("*"); pendingStar = false; }
        if (headingHashes > 0) {
            for (int j = 0; j < headingHashes; j++) delegate.onToken("#");
            headingHashes = 0;
        }
        if (bold) { delegate.onToken(BOLD_OFF); bold = false; }
        delegate.onComplete();
    }

    @Override public void onError(Exception e) {
        if (pendingStar) { delegate.onToken("*"); pendingStar = false; }
        if (headingHashes > 0) {
            for (int j = 0; j < headingHashes; j++) delegate.onToken("#");
            headingHashes = 0;
        }
        if (bold) { delegate.onToken(BOLD_OFF); bold = false; }
        delegate.onError(e);
    }

    @Override public void onToolCall(ToolCall call) { delegate.onToolCall(call); }
    @Override public void onUsage(int in, int out) { delegate.onUsage(in, out); }
}
