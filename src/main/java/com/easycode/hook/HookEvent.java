package com.easycode.hook;

/** Hook 触发事件 */
public enum HookEvent {
    // 系统级
    STARTUP,
    SHUTDOWN,
    // 会话级
    SESSION_START,
    SESSION_END,
    // 轮次级
    TURN_START,
    TURN_END,
    // 消息级
    PRE_LLM_REQUEST,
    POST_LLM_RESPONSE,
    // 工具级
    PRE_TOOL,
    POST_TOOL
}
