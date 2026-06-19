package com.easycode.permission;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class SandboxLayer implements PermissionLayer {

    private static final Set<String> FILE_TOOLS = Set.of(
        "read_file", "write_file", "edit_file", "find_files", "grep_code"
    );

    @Override
    public PermissionResult check(ToolCall call, Tool tool, PermissionContext ctx) {
        if (!FILE_TOOLS.contains(tool.name())) return PermissionResult.NOT_APPLICABLE;
        String pathStr = extractPath(call, tool);
        if (pathStr == null || pathStr.isEmpty()) return PermissionResult.NOT_APPLICABLE;
        try {
            Path target = Path.of(pathStr);
            Path projectRoot = ctx.projectRoot().toRealPath();

            if (!target.isAbsolute()) {
                target = ctx.projectRoot().resolve(target);
            }

            if (Files.exists(target)) {
                Path realTarget = target.toRealPath();
                if (!realTarget.startsWith(projectRoot))
                    return PermissionResult.DENY;
            } else {
                // 目标不存在——找最近的已存在祖先目录
                Path ancestor = target;
                while (ancestor != null && !Files.exists(ancestor)) {
                    ancestor = ancestor.getParent();
                }
                if (ancestor != null) {
                    Path realAncestor = ancestor.toRealPath();
                    if (!realAncestor.startsWith(projectRoot))
                        return PermissionResult.DENY;
                }
            }
            return PermissionResult.NOT_APPLICABLE;
        } catch (IOException e) {
            return PermissionResult.DENY;
        }
    }

    private String extractPath(ToolCall call, Tool tool) {
        if (call.input().has("path")) return call.input().get("path").asText();
        if (call.input().has("dir")) return call.input().get("dir").asText();
        return null;
    }

    @Override
    public String deniedReason(ToolCall call, Tool tool, PermissionContext ctx) {
        return "路径超出项目目录范围";
    }
}
