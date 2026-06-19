package com.easycode.permission;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Comparator;
import java.util.List;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.Map;

public final class RuleEngine {

    private static final Map<String, String> FRIENDLY_MAP = Map.of(
        "Read", "read_file", "Write", "write_file", "Edit", "edit_file",
        "Bash", "exec_command", "Glob", "find_files", "Grep", "grep_code"
    );

    private final List<Rule> rules;
    private final String defaultMode;

    public record Rule(String toolName, String pattern, boolean allow, RuleLevel level) {
        public enum RuleLevel { LOCAL, PROJECT, USER }

        public static Rule parse(String entry, RuleLevel level) {
            int paren = entry.indexOf('(');
            String tool = paren > 0 ? entry.substring(0, paren).trim() : entry.trim();
            String pat = null;
            boolean isDeny = false;
            if (paren > 0) {
                int close = entry.lastIndexOf(')');
                String inner = (close > paren) ? entry.substring(paren + 1, close).trim() : "";
                if (inner.startsWith("!")) { isDeny = true; inner = inner.substring(1).trim(); }
                if (!inner.isEmpty()) pat = inner;
            }
            String realTool = FRIENDLY_MAP.getOrDefault(tool, tool);
            return new Rule(realTool, pat, !isDeny, level);
        }
    }

    public RuleEngine(List<Rule> rules, String defaultMode) {
        this.rules = rules.stream()
            .sorted(Comparator.comparingInt(r -> r.level().ordinal())) // LOCAL > PROJECT > USER
            .toList();
        this.defaultMode = defaultMode;
    }

    public String defaultMode() { return defaultMode; }

    /** 返回 null=未命中, true=allow, false=deny */
    public Boolean match(ToolCall call, Tool tool) {
        String toolName = tool.name();
        for (Rule r : rules) {
            if (!r.toolName().equals(toolName)) continue;
            if (r.pattern() == null) return r.allow();
            if (matchesPattern(call, tool, r.pattern())) return r.allow();
        }
        return null;
    }

    private boolean matchesPattern(ToolCall call, Tool tool, String pattern) {
        if ("exec_command".equals(tool.name())) {
            String cmd = call.input().has("command") ? call.input().get("command").asText() : "";
            return globMatch(pattern, cmd);
        }
        String path = extractPath(call);
        if (path == null) return false;
        return globMatch(pattern, path);
    }

    private String extractPath(ToolCall call) {
        if (call.input().has("path")) return call.input().get("path").asText();
        if (call.input().has("dir")) return call.input().get("dir").asText();
        return null;
    }

    private boolean globMatch(String pattern, String target) {
        // 文件路径使用 PathMatcher glob，命令串使用简单通配
        if (pattern.contains("/") || pattern.contains("**")) {
            try {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                return matcher.matches(Path.of(target));
            } catch (Exception e) { return false; }
        }
        // 简单通配：* 匹配任意字符序列
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return target.matches(regex);
    }
}
