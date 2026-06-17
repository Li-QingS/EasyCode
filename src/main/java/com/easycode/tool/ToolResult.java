package com.easycode.tool;

/** 工具执行结果 */
public record ToolResult(
    String toolName,
    boolean success,
    String content,
    long durationMs
) {
    /** 成功快捷构造 */
    public static ToolResult ok(String toolName, String content, long durationMs) {
        return new ToolResult(toolName, true, content, durationMs);
    }

    /** 失败快捷构造 */
    public static ToolResult err(String toolName, String error, long durationMs) {
        return new ToolResult(toolName, false, error, durationMs);
    }
}
