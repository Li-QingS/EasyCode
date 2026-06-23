package com.easycode.subagent;

import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

/** 构建 Worktree 路径重定向后的 ToolRegistry */
public final class WorktreedToolRegistry {

    private static final ObjectMapper json = new ObjectMapper();

    private WorktreedToolRegistry() {}

    /** 基于父 ToolRegistry 创建路径重定向的新 ToolRegistry */
    public static ToolRegistry wrap(ToolRegistry parent, Path worktreeRoot) {
        ToolRegistry wrapped = new ToolRegistry();
        for (Tool tool : parent.all()) {
            wrapped.register(wrapTool(tool, worktreeRoot));
        }
        return wrapped;
    }

    private static Tool wrapTool(Tool original, Path worktreeRoot) {
        return switch (original.category()) {
            case FILE -> wrapFileTool(original, worktreeRoot);
            case SHELL -> wrapShellTool(original, worktreeRoot);
            default -> original; // SEARCH 等只读工具透传
        };
    }

    /** 包装文件操作工具：read_file / write_file / edit_file */
    private static Tool wrapFileTool(Tool original, Path worktreeRoot) {
        return new Tool() {
            @Override public String name() { return original.name(); }
            @Override public String description() { return original.description(); }
            @Override public Category category() { return original.category(); }
            @Override public Permission permission() { return original.permission(); }
            @Override public JsonNode inputSchema() { return original.inputSchema(); }

            @Override
            public ToolResult execute(JsonNode input) {
                ObjectNode cloned = input.deepCopy();
                if (cloned.has("path")) {
                    String relativePath = cloned.get("path").asText();
                    Path resolved = worktreeRoot.resolve(
                        relativePath.startsWith("/") ? relativePath.substring(1) : relativePath)
                        .normalize();
                    if (!resolved.startsWith(worktreeRoot)) {
                        return ToolResult.err(original.name(),
                            "路径越界: " + relativePath + " 超出 Worktree 范围", 0);
                    }
                    cloned.put("path", resolved.toString());
                }
                return original.execute(cloned);
            }
        };
    }

    /** 包装 shell 工具：exec_command */
    private static Tool wrapShellTool(Tool original, Path worktreeRoot) {
        return new Tool() {
            @Override public String name() { return original.name(); }
            @Override public String description() { return original.description(); }
            @Override public Category category() { return original.category(); }
            @Override public Permission permission() { return original.permission(); }
            @Override public JsonNode inputSchema() { return original.inputSchema(); }

            @Override
            public ToolResult execute(JsonNode input) {
                ObjectNode cloned = input.deepCopy();
                if (!cloned.has("workingDir")) {
                    cloned.put("workingDir", worktreeRoot.toString());
                }
                return original.execute(cloned);
            }
        };
    }
}
