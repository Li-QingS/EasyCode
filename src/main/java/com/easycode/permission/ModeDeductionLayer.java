package com.easycode.permission;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;

public final class ModeDeductionLayer implements PermissionLayer {

    private static final PermissionResult[][] MATRIX = {
        // DEFAULT:             只读    文件写   命令执行
        { PermissionResult.ALLOW, PermissionResult.ASK, PermissionResult.ASK },
        // ACCEPT_EDITS:
        { PermissionResult.ALLOW, PermissionResult.ALLOW, PermissionResult.ASK },
        // PLAN:
        { PermissionResult.ALLOW, PermissionResult.ASK, PermissionResult.ASK },
        // BYPASS_PERMISSIONS:
        { PermissionResult.ALLOW, PermissionResult.ALLOW, PermissionResult.ALLOW },
    };

    @Override
    public PermissionResult check(ToolCall call, Tool tool, PermissionContext ctx) {
        int row = ctx.mode().ordinal();
        int col = categoryIndex(tool);
        return MATRIX[row][col];
    }

    private int categoryIndex(Tool tool) {
        if (tool.permission() == Tool.Permission.READ_ONLY) return 0;
        // 文件名匹配仅用于区分文件写和命令执行
        String name = tool.name();
        if ("read_file".equals(name) || "find_files".equals(name) || "grep_code".equals(name)) return 0;
        if ("write_file".equals(name) || "edit_file".equals(name)) return 1;
        return 2; // exec_command 及其他
    }

    @Override
    public String deniedReason(ToolCall call, Tool tool, PermissionContext ctx) {
        return "当前权限模式要求确认此操作";
    }
}
