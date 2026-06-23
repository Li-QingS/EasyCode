package com.easycode.team.lead;

import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;

import java.util.List;
import java.util.Set;

/** Coordinator 模式：环境变量激活 + 工具裁剪 */
public final class CoordinatorMode {

    private CoordinatorMode() {}

    /** 检查 EASYCODE_COORDINATOR=1 是否启用 */
    public static boolean isActive() {
        return "1".equals(System.getenv("EASYCODE_COORDINATOR"));
    }

    /** 裁剪 ToolRegistry：移除写文件工具，保留读+shell */
    public static ToolRegistry filterTools(ToolRegistry original) {
        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : original.all()) {
            if ("write_file".equals(tool.name()) || "edit_file".equals(tool.name())) {
                continue; // 剥夺写文件权限
            }
            filtered.register(tool);
        }
        return filtered;
    }

    /** 根据裁剪后的 ToolRegistry 判断 toolsJson 是否启用 Coordinator */
    public static List<com.fasterxml.jackson.databind.JsonNode> filteredToolsJson(
            ToolRegistry registry, java.util.Set<String> whitelist) {
        ToolRegistry filtered = filterTools(registry);
        return filtered.toToolsJson(whitelist != null ? whitelist : java.util.Collections.emptySet());
    }
}
