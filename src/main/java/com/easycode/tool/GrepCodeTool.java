package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public final class GrepCodeTool implements Tool {
    private static final ObjectMapper json = new ObjectMapper();
    private static final int MAX_MATCHES = 100;
    private static final int MAX_OUTPUT = 4000;

    @Override public String name() { return "grep_code"; }

    @Override public String description() {
        return "用正则表达式逐文件逐行搜索代码内容, 自动排除二进制文件和 .git 等目录。" +
            "返回格式: 文件路径:行号:匹配行内容, 最多 " + MAX_MATCHES + " 条。" +
            "参数 pattern(必填): 正则表达式。dir(可选): 搜索目录, 默认项目根。" +
            "fileFilter(可选): glob 过滤文件名, 如 '*.java'。contextLines(可选): 显示匹配行前后各 N 行上下文。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = json.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("pattern").put("type", "string").put("description", "正则表达式");
        props.putObject("dir").put("type", "string").put("description", "搜索目录, 默认项目根");
        props.putObject("fileFilter").put("type", "string").put("description", "glob 过滤文件名, 如 *.java");
        props.putObject("contextLines").put("type", "integer").put("description", "显示匹配行前后各 N 行");
        s.putArray("required").add("pattern");
        return s;
    }

    @Override
    public ToolResult execute(JsonNode input) {
        long start = System.currentTimeMillis();
        try {
            String pattern = input.get("pattern").asText();
            String dir = input.has("dir") && !input.get("dir").isNull()
                    ? input.get("dir").asText() : ".";
            String fileFilter = input.has("fileFilter") && !input.get("fileFilter").isNull()
                    ? input.get("fileFilter").asText() : null;
            int context = input.has("contextLines") ? input.get("contextLines").asInt() : 0;

            // 构建 rg 命令
            List<String> cmd = new ArrayList<>();
            cmd.add("rg");
            cmd.add("--line-number");
            cmd.add("--no-heading");
            cmd.add("--color");
            cmd.add("never");
            cmd.add("--max-count=" + MAX_MATCHES);
            if (fileFilter != null) { cmd.add("-g"); cmd.add(fileFilter); }
            if (context > 0) { cmd.add("-C"); cmd.add(String.valueOf(context)); }
            cmd.add(pattern);
            cmd.add(dir);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            proc.waitFor();
            String output = sb.toString().trim();
            if (output.isEmpty()) return ToolResult.ok(name(), "未找到匹配", System.currentTimeMillis() - start);
            if (output.length() > MAX_OUTPUT)
                output = output.substring(0, MAX_OUTPUT) + "\n... (截断)";
            return ToolResult.ok(name(), output, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.err(name(), "搜索失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override public Permission permission() { return Permission.READ_ONLY; }
    @Override public boolean requiresApproval() { return false; }
    @Override public Category category() { return Category.SEARCH; }
    @Override public boolean isDestructive() { return false; }
    @Override public State defaultState() { return State.ENABLED; }
}
