package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** 工具统一接口：每个工具自描述（名称、描述、参数 Schema）并可执行 */
public interface Tool {
    /** 工具名，下划线格式，如 "read_file" */
    String name();

    /** 人类可读的功能描述，给模型看的 */
    String description();

    /** JSON Schema 描述输入参数 */
    JsonNode inputSchema();

    /** 执行工具，传入参数 JSON，返回结构化结果 */
    ToolResult execute(JsonNode input);

    // ====== 共享路径处理 ======

    Pattern WINDOWS_DRIVE = Pattern.compile("^[a-zA-Z]:/.*");

    /** 路径清洗 + 安全检查，返回相对于项目根目录的 Path */
    default Path resolvePath(String rawPath) {
        // 清洗
        String path = rawPath.replace('\\', '/').trim();
        if (WINDOWS_DRIVE.matcher(path).matches())
            path = "/mnt/" + path.substring(0, 1).toLowerCase() + path.substring(2);
        // 安全
        Path filePath = Path.of(path);
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        if (filePath.isAbsolute()) {
            Path resolved = filePath.normalize();
            // 项目内路径 + 系统临时目录（测试用）
            if (resolved.startsWith(projectRoot) || resolved.startsWith(Path.of("/tmp")))
                return projectRoot.relativize(resolved);
            throw new IllegalArgumentException("路径不在项目内: " + rawPath);
        }
        filePath = filePath.normalize();
        if (filePath.startsWith(".."))
            throw new IllegalArgumentException("路径不允许跳出项目: " + rawPath);
        return filePath;
    }

    // ====== 权限与安全元数据 ======

    /** 权限级别 */
    enum Permission { READ_ONLY, READ_WRITE }

    /** 工具权限：只读或读写 */
    default Permission permission() { return Permission.READ_WRITE; }

    /** 执行前是否需要用户在终端确认 */
    default boolean requiresApproval() { return false; }

    /** 是否为破坏性工具（如 exec_command 可执行任意命令） */
    default boolean isDestructive() { return false; }

    /** 默认启用状态 */
    enum State { ENABLED, DISABLED, APPROVAL_REQUIRED }

    /** 工具的默认启用状态 */
    default State defaultState() { return State.ENABLED; }
}
