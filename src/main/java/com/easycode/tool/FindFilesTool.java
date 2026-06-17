package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class FindFilesTool implements Tool {
    private static final ObjectMapper json = new ObjectMapper();
    private static final int MAX_FILES = 200;
    // 排除的无意义目录
    private static final Set<String> EXCLUDED_DIRS = Set.of(
        "node_modules", "vendor", ".git", ".svn", "__pycache__",
        "target", "build", "dist", ".idea", ".vscode", ".gradle",
        "venv", ".venv", "env", ".env", ".tox", ".mypy_cache",
        ".pytest_cache", ".ruff_cache", "bower_components"
    );

    @Override public String name() { return "find_files"; }

    @Override public String description() {
        return "按 glob 模式递归查找文件, 自动排除 node_modules/.git/target 等无意义目录。" +
            "结果按修改时间倒序(最近修改的在前), 最多返回 " + MAX_FILES + " 个文件, 每行一个相对路径。" +
            "参数 pattern(必填): glob 模式, 如 *.java。参数 dir(可选): 搜索起始目录, 默认项目根目录。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = json.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("pattern").put("type", "string").put("description", "glob 模式, 如 *.java");
        props.putObject("dir").put("type", "string").put("description", "搜索目录, 默认项目根目录");
        s.putArray("required").add("pattern");
        return s;
    }

    @Override
    public ToolResult execute(JsonNode input) {
        long start = System.currentTimeMillis();
        try {
            String pattern = input.get("pattern").asText();
            Path dir = input.has("dir") && !input.get("dir").isNull()
                    ? Path.of(input.get("dir").asText()) : Path.of(".");
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            // 收集：路径 + 修改时间
            List<Map.Entry<Path, Long>> entries = new ArrayList<>();
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (EXCLUDED_DIRS.contains(d.getFileName().toString()))
                        return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = dir.relativize(file);
                    if (matcher.matches(rel) && entries.size() < MAX_FILES)
                        entries.add(Map.entry(rel, attrs.lastModifiedTime().toMillis()));
                    return FileVisitResult.CONTINUE;
                }
            });

            // 按修改时间倒序
            entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

            if (entries.isEmpty())
                return ToolResult.ok(name(), "未找到匹配文件", System.currentTimeMillis() - start);

            StringBuilder sb = new StringBuilder();
            for (var e : entries)
                sb.append(e.getKey()).append('\n');
            return ToolResult.ok(name(), sb.toString().trim(), System.currentTimeMillis() - start);
        } catch (IOException e) {
            return ToolResult.err(name(), "查找文件失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override public Permission permission() { return Permission.READ_ONLY; }
    @Override public boolean requiresApproval() { return false; }
    @Override public boolean isDestructive() { return false; }
    @Override public State defaultState() { return State.ENABLED; }
}
