package com.easycode.context;

import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;

/**
 * Token 估算器：锚定上次 API 返回的 inputTokens，对新增消息用字符数/3 估算增量。
 */
public final class TokenEstimator {
    public TokenEstimator() {}
    // {} //
    private static final double CHARS_PER_TOKEN = Constants.ESTIMATE_CHARS_PER_TOKEN;
    private long anchorTokens;


    /** 更新锚点：最近一次 provider 返回的真实 usage 之和（F14） */
    public void updateAnchor(int inputTokens, int cacheReadTokens, int cacheCreationTokens, int outputTokens) {
        this.anchorTokens = (long) inputTokens + cacheReadTokens + cacheCreationTokens + outputTokens;
    }

    /** 估算当前对话历史的总 token 数。取 max(增量估算, 锚点) 作为下界 */
    public long estimate(ConversationMgr conv) {
        long totalChars = countChars(conv.getHistory());
        long estimated = (long) (totalChars / CHARS_PER_TOKEN);
        return Math.max(estimated, anchorTokens);
    }

    /** 直接返回锚点值 */
    public long getAnchor() {
        return anchorTokens;
    }

    /** 重置锚点（如紧急压缩后） */
    public void reset() {
        this.anchorTokens = 0;
    }

    // ---------- 以下为原有静态方法，改为实例方法后保留兼容性 ----------

    /** @deprecated 使用实例方法 estimate(ConversationMgr) */
    @Deprecated
    public static int estimate(ConversationMgr conv, int lastApiInputTokens) {
        long totalChars = countChars(conv.getHistory());
        int estimated = (int)(totalChars / CHARS_PER_TOKEN);
        return Math.max(estimated, lastApiInputTokens);
    }

    /** 估算自上次 API 调用以来新增消息的 token 数 */
    public static int estimateNew(ConversationMgr conv, int knownMessageCount, int lastApiInputTokens) {
        var history = conv.getHistory();
        if (knownMessageCount >= history.size()) return 0;
        long newChars = 0;
        for (int i = knownMessageCount; i < history.size(); i++) {
            newChars += messageChars(history.get(i));
        }
        return (int) (newChars / CHARS_PER_TOKEN);
    }

    private static long countChars(java.util.List<MessageRecord> messages) {
        long total = 0;
        for (MessageRecord m : messages) {
            total += messageChars(m);
        }
        return total;
    }

    private static long messageChars(MessageRecord m) {
        long chars = m.content() != null ? m.content().length() : 0;
        for (MessageBlock b : m.blocks()) {
            if (b instanceof MessageBlock.TextBlock tb) chars += tb.text().length();
            else if (b instanceof MessageBlock.ToolResultBlock tr) chars += tr.content().length();
            else if (b instanceof MessageBlock.ToolUseBlock tu) {
                chars += tu.name().length();
                chars += tu.input() != null ? tu.input().toString().length() : 0;
            }
        }
        return chars;
    }

    /** 单条消息的字符数 */
    public static long messageCharCount(MessageRecord m) {
        return messageChars(m);
    }
}
