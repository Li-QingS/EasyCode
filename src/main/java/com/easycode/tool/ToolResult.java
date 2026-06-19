package com.easycode.tool;

public record ToolResult(
    String toolName,
    boolean success,
    String content,
    long durationMs,
    boolean askPending
) {
    public ToolResult(String toolName, boolean success, String content, long durationMs) {
        this(toolName, success, content, durationMs, false);
    }

    public static ToolResult ok(String toolName, String content, long durationMs) {
        return new ToolResult(toolName, true, content, durationMs);
    }
    public static ToolResult err(String toolName, String error, long durationMs) {
        return new ToolResult(toolName, false, error, durationMs);
    }
    public static ToolResult askPending(String toolName, String reason) {
        return new ToolResult(toolName, false, reason, 0, true);
    }
}
