package com.easycode.hook;

/** 子 Agent 占位动作 */
public record SubAgentAction() implements HookAction {

    @Override
    public String type() { return "sub-agent"; }

    @Override
    public String execute(HookContext ctx) {
        return "[sub-agent not yet implemented]";
    }
}
