package com.easycode.permission;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;

public interface PermissionLayer {
    PermissionResult check(ToolCall call, Tool tool, PermissionContext ctx);
    String deniedReason(ToolCall call, Tool tool, PermissionContext ctx);
}
