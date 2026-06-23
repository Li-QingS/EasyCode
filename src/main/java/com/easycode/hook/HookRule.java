package com.easycode.hook;

/** 单条 Hook 规则 */
public record HookRule(
    String name,
    HookEvent event,
    ConditionNode condition,  // null = 无条件
    HookAction action,
    boolean once,
    boolean async
) {
    public HookRule {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (event == null) throw new IllegalArgumentException("event is required");
        if (action == null) throw new IllegalArgumentException("action is required");
    }
}
