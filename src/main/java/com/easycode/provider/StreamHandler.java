package com.easycode.provider;

/** 流式响应回调接口 */
public interface StreamHandler {
    void onToken(String token);

    default void onThinking(String thinking) {}
    default void onToolCall(ToolCall call) {}
    default void onUsage(int inputTokens, int outputTokens, int cacheWriteTokens, int cacheReadTokens) {}
    default void onComplete() {}
    default void onError(Exception e) {}
}
