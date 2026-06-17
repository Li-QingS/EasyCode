package com.easycode.provider;

/** 流式响应回调接口 */
public interface StreamHandler {
    void onToken(String token);

    default void onToolCall(ToolCall call) {}
    default void onUsage(int inputTokens, int outputTokens) {}
    default void onComplete() {}
    default void onError(Exception e) {}
}
