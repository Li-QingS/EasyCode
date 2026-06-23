package com.easycode.hook;

import java.util.Map;

/** Hook 执行上下文 */
public record HookContext(HookEvent event, Map<String, Object> vars) {}
