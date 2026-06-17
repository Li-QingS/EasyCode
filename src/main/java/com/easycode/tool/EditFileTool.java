package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class EditFileTool implements Tool {
    private static final ObjectMapper json = new ObjectMapper();
    private static final int CONTEXT_LINES = 2;

    @Override public String name() { return "edit_file"; }

    @Override public String description() {
        return "精确替换文件中的一段文本，要求原文在文件中仅出现一次。" +
            "参数 path: 文件路径。old: 要替换的原文(建议包含前后至少一行上下文确保唯一)。new: 新文本。" +
            "返回改动处的上下文 diff(- 旧行, + 新行), 而非完整文件。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = json.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "文件路径");
        props.putObject("old").put("type", "string").put("description", "要替换的原文(必须唯一匹配)");
        props.putObject("new").put("type", "string").put("description", "替换为的新文本");
        s.putArray("required").add("path").add("old").add("new");
        return s;
    }

    @Override
    public ToolResult execute(JsonNode input) {
        long start = System.currentTimeMillis();
        try {
            Path p = resolvePath(input.get("path").asText());
            String oldText = input.get("old").asText();
            String newText = input.get("new").asText();
            String content = Files.readString(p);

            int count = countMatches(content, oldText);
            if (count == 0)
                return ToolResult.err(name(), "未找到匹配: " + truncate(oldText, 80), System.currentTimeMillis() - start);
            if (count > 1)
                return ToolResult.err(name(), "匹配到 " + count + " 处, 不唯一", System.currentTimeMillis() - start);

            String replaced = content.replace(oldText, newText);
            Files.writeString(p, replaced);
            String diff = buildDiff(content, oldText, newText, p.toString());
            return ToolResult.ok(name(), diff, System.currentTimeMillis() - start);
        } catch (IllegalArgumentException e) {
            return ToolResult.err(name(), "[安全] " + e.getMessage(), 0);
        } catch (IOException e) {
            return ToolResult.err(name(), "编辑失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private int countMatches(String content, String sub) {
        int count = 0, idx = 0;
        while ((idx = content.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String buildDiff(String orig, String oldText, String newText, String path) {
        int idx = orig.indexOf(oldText);
        if (idx < 0) return "替换成功: " + path;
        String before = orig.substring(0, idx);
        int startLine = before.isEmpty() ? 1 : before.split("\n", -1).length;
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);
        int endLine = startLine + oldLines.length - 1;
        String[] allLines = orig.split("\n", -1);
        int ctxStart = Math.max(0, startLine - 1 - CONTEXT_LINES);
        int ctxEnd = Math.min(allLines.length, endLine + CONTEXT_LINES);

        StringBuilder sb = new StringBuilder();
        sb.append(path).append(" L").append(startLine);
        if (endLine != startLine) sb.append("-L").append(endLine);
        sb.append(":\n");
        for (int i = ctxStart; i < ctxEnd; i++) {
            int ln = i + 1;
            if (ln < startLine) sb.append("  ").append(ln).append("|").append(allLines[i]).append('\n');
            else if (ln > endLine) sb.append("  ").append(ln).append("|").append(allLines[i]).append('\n');
            else {
                int relIdx = ln - startLine;
                if (relIdx < oldLines.length) sb.append("- ").append(ln).append("|").append(oldLines[relIdx]).append('\n');
                if (relIdx < newLines.length) sb.append("+ ").append(ln).append("|").append(newLines[relIdx]).append('\n');
            }
        }
        return sb.toString().trim();
    }

    @Override public Permission permission() { return Permission.READ_WRITE; }
    @Override public boolean requiresApproval() { return true; }
    @Override public Category category() { return Category.FILE; }
    @Override public boolean isDestructive() { return false; }
    @Override public State defaultState() { return State.APPROVAL_REQUIRED; }
}
