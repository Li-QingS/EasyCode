package com.easycode.hook;

/** 提示词注入动作 */
public record PromptAction(String text) implements HookAction {
    public PromptAction {
        if (text == null) text = "";
    }

    @Override
    public String type() { return "prompt"; }

    @Override
    public String execute(HookContext ctx) {
        return text;
    }
}
