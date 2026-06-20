package com.easycode.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/** @include 展开引擎（F2-F6）。递归解析 @include 指令，处理嵌套深度/环路/逃逸。 */
final class IncludeResolver {
    private static final int MAX_DEPTH = 5;
    private static final Pattern INCLUDE_LINE = Pattern.compile("^@include\\s+(.+)$");

    private IncludeResolver() {}

    /**
     * @param content      源内容
     * @param baseDir      当前文件所在目录
     * @param rootBoundary 安全边界（项目级=projectRoot，用户级=~/.easycode/）
     * @param depth        当前深度（1=原始文件）
     * @param visited      已访问的规范绝对路径集合
     */
    static String resolve(String content, Path baseDir, Path rootBoundary,
                          int depth, Set<String> visited) {
        StringBuilder result = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            var matcher = INCLUDE_LINE.matcher(trimmed);
            // F2: 独占一行的 @include 才展开
            if (matcher.matches() && trimmed.equals(line.trim())) {
                String relPath = matcher.group(1).trim();
                String expanded = expandInclude(relPath, baseDir, rootBoundary, depth, visited);
                if (expanded.startsWith("<!--")) {
                    result.append(expanded).append('\n');
                } else {
                    result.append(expanded);
                }
            } else {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    private static String expandInclude(String relPath, Path baseDir, Path rootBoundary,
                                        int depth, Set<String> visited) {
        if (depth >= MAX_DEPTH) {
            return "<!-- @include 超过最大嵌套深度，已跳过: " + relPath + " -->";
        }
        try {
            Path resolved = baseDir.resolve(relPath).normalize().toRealPath();
            // F5: 路径逃逸检测
            if (!resolved.startsWith(rootBoundary.toRealPath())) {
                return "<!-- @include 路径超出允许范围，已跳过: " + relPath + " -->";
            }
            // F4: 环路检测
            String absKey = resolved.toString();
            if (!visited.add(absKey)) {
                return "<!-- @include 检测到环路，已跳过: " + relPath + " -->";
            }
            // F6: 二进制检测
            byte[] head = new byte[512];
            try (var is = Files.newInputStream(resolved)) {
                int n = is.read(head);
                for (int i = 0; i < n; i++) {
                    if (head[i] == 0) return "<!-- @include 二进制文件，已跳过: " + relPath + " -->";
                }
            }
            String fileContent = Files.readString(resolved, StandardCharsets.UTF_8);
            return resolve(fileContent, resolved.getParent(), rootBoundary, depth + 1, visited);
        } catch (IOException e) {
            // F6: 文件不存在/不可读，静默跳过
            return "<!-- @include 文件无法读取，已跳过: " + relPath + " -->";
        }
    }
}
