package com.easycode.context;

/** 压缩事件——发给 TUI 用于展示系统消息（N5）。 */
public record CompressEvent(
    CompressReason reason,
    long estimatedBefore,
    long estimatedAfter,
    int replacedCount,
    boolean success,
    String errorMessage
) {
    public enum CompressReason { AUTO, MANUAL, EMERGENCY }

    public static CompressEvent ok(CompressReason reason, long before, long after, int replaced) {
        return new CompressEvent(reason, before, after, replaced, true, null);
    }

    public static CompressEvent fail(CompressReason reason, String error) {
        return new CompressEvent(reason, 0, 0, 0, false, error);
    }

    /** 适合 TUI 展示的单行摘要 */
    public String toDisplay() {
        if (!success) return "压缩失败: " + (errorMessage != null ? errorMessage : "未知错误");
        return "已压缩，token 从 " + estimatedBefore + " 降至 " + estimatedAfter;
    }
}
