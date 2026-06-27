package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteFileTool implements Tool {
    private static final ObjectMapper json = new ObjectMapper();
    private static final int MAX_CONTENT = 500_000; // 500KB 上限

    @Override public String name() { return "write_file"; }

    @Override public String description() {
        return "创建新文件或完全覆盖已有文件。文件不存在时自动创建（含父目录），文件存在时原有内容永久丢失。" +
            "不要用它修改文件的部分内容（用 edit_file 精确替换），不要用它读取文件（用 read_file）。" +
            "参数 path（string，必填）：文件路径，支持相对和绝对路径，统一用正斜杠 /。" +
            "参数 content（string，必填）：要写入的完整内容。内容超过 500KB 会被拒绝。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = json.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "文件路径");
        props.putObject("content").put("type", "string").put("description", "写入的内容");
        s.putArray("required").add("path").add("content");
        return s;
    }

    @Override
    public ToolResult execute(JsonNode input) {
        long start = System.currentTimeMillis();
        try {
            Path filePath = resolvePath(input.get("path").asText());
            String content = input.get("content").asText();
            if (content.length() > MAX_CONTENT)
                return ToolResult.err(name(), "[过大] 内容超过 500KB", 0);

            if (filePath.getParent() != null)
                Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            long size = Files.size(filePath);
            return ToolResult.ok(name(), "写入成功: " + filePath + " (" + size + " 字节)",
                    System.currentTimeMillis() - start);
        } catch (IllegalArgumentException e) {
            return ToolResult.err(name(), "[安全] " + e.getMessage(), 0);
        } catch (IOException e) {
            return ToolResult.err(name(), "写入失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override public Permission permission() { return Permission.READ_WRITE; }
    @Override public boolean requiresApproval() { return true; }
    @Override public Category category() { return Category.FILE; }
    @Override public boolean isDestructive() { return false; }
    @Override public State defaultState() { return State.APPROVAL_REQUIRED; }
}
