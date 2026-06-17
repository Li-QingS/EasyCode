package com.easycode.agent;

import com.easycode.provider.StreamHandler;
import com.easycode.provider.ToolCall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public final class StreamingCollector implements StreamHandler {

    private final Consumer<AgentEvent> eventSink;
    private final StringBuilder fullText = new StringBuilder();
    private final StringBuilder thinkingText = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private int roundInputTokens;
    private int roundOutputTokens;
    private int cacheWriteTokens;
    private int cacheReadTokens;
    private boolean completed;
    private boolean hasError;
    private String errorMessage;

    public StreamingCollector(Consumer<AgentEvent> eventSink) {
        this.eventSink = eventSink;
    }

    @Override public void onToken(String token) {
        eventSink.accept(new AgentEvent.TextDelta(token));
        fullText.append(token);
    }
    @Override public void onThinking(String thinking) { thinkingText.append(thinking); }
    @Override public void onToolCall(ToolCall call) { toolCalls.add(call); }
    @Override public void onUsage(int in, int out, int cacheWrite, int cacheRead) {
        this.roundInputTokens = in; this.roundOutputTokens = out;
        this.cacheWriteTokens = cacheWrite; this.cacheReadTokens = cacheRead;
    }
    @Override public void onComplete() { this.completed = true; }
    @Override public void onError(Exception e) {
        this.hasError = true; this.errorMessage = e.getMessage();
        eventSink.accept(new AgentEvent.Error(e.getMessage(), true));
    }

    public List<ToolCall> getToolCalls() { return Collections.unmodifiableList(toolCalls); }
    public String getFullText() { return fullText.toString(); }
    public String getThinkingText() { return thinkingText.toString(); }
    public int getRoundInputTokens() { return roundInputTokens; }
    public int getRoundOutputTokens() { return roundOutputTokens; }
    public int getCacheWriteTokens() { return cacheWriteTokens; }
    public int getCacheReadTokens() { return cacheReadTokens; }
    public boolean isCompleted() { return completed; }
    public boolean hasError() { return hasError; }
    public String getErrorMessage() { return errorMessage; }
}
