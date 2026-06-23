package com.easycode.hook;

/** Hook 动作接口 */
public interface HookAction {
    String execute(HookContext ctx) throws Exception;
    String type();
}
