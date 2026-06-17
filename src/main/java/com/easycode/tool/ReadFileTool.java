package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class ReadFileTool implements Tool {
    private static final ObjectMapper json = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 200;
    private static final long MAX_FILE_SIZE = 1_048_576; // 1MB
    // 二进制扩展名黑名单
    private static final Set<String> BINARY_EXT = Set.of(
        "class","jar","war","exe","dll","so","dylib",
        "png","jpg","jpeg","gif","bmp","ico",
        "zip","tar","gz","bz2","xz","7z","rar",
        "mp3","mp4","avi","mov","wmv","flv",
        "pdf","doc","docx","xls","xlsx","ppt","pptx",
        "ttf","otf","woff","woff2","eot",
        "o","obj","lib","a","bin","dat","db","sqlite"
    );
    private static final int SAMPLE_SIZE = 512;
    private static final double BINARY_RATIO = 0.3;

    @Override public String name() { return "read_file"; }

    @Override public String description() {
        return "读取项目中的文件内容，每行带行号（格式：行号|内容）。" +
            "支持相对路径（如 src/main/java/Main.java）和绝对路径（如 /mnt/d/agent project/EasyCode/src/...）。" +
            "路径统一使用正斜杠 /，如果用户给出反斜杠 \\ 的路径，请转换为 / 后再传入。" +
            "参数 path（必填）：文件路径。offset（可选，默认 1）：起始行号。limit（可选，默认 200）：最大行数。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = json.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "文件路径，相对于项目根目录");
        props.putObject("offset").put("type", "integer").put("description", "从第几行开始读，默认 1");
        props.putObject("limit").put("type", "integer").put("description", "最多读多少行，默认 200");
        s.putArray("required").add("path");
        return s;
    }

    @Override
    public ToolResult execute(JsonNode input) {
        long start = System.currentTimeMillis();
        try {
            Path filePath = resolvePath(input.get("path").asText());
            if (!Files.exists(filePath))
                return ToolResult.err(name(), "[未找到] 文件不存在: " + filePath, 0);
            long size = Files.size(filePath);
            if (size > MAX_FILE_SIZE)
                return ToolResult.err(name(), "[过大] 文件超过 1MB: " + filePath, 0);
            if (isBinary(filePath))
                return ToolResult.err(name(), "[不可读] 二进制或无法识别的文件: " + filePath, 0);

            List<String> allLines = Files.readAllLines(filePath);
            int total = allLines.size();
            int offset = input.has("offset") ? input.get("offset").asInt() : 1;
            int limit = input.has("limit") ? input.get("limit").asInt() : DEFAULT_LIMIT;
            if (offset < 1) offset = 1;
            if (limit < 1) limit = DEFAULT_LIMIT;

            int startLine = offset - 1;
            int endLine = Math.min(startLine + limit, total);

            StringBuilder sb = new StringBuilder();
            for (int i = startLine; i < endLine; i++)
                sb.append(i + 1).append('|').append(allLines.get(i)).append('\n');

            if (endLine < total)
                sb.append("... (文件共 ").append(total).append(" 行，已显示 ")
                  .append(offset).append("-").append(endLine)
                  .append("。要继续读取请立即用 read_file offset=").append(endLine + 1).append(")");
            else
                sb.append("(文件共 ").append(total).append(" 行)");

            return ToolResult.ok(name(), sb.toString(), System.currentTimeMillis() - start);
        } catch (IOException e) {
            return ToolResult.err(name(), "读文件失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private boolean isBinary(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && BINARY_EXT.contains(name.substring(dot + 1).toLowerCase())) return true;
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[SAMPLE_SIZE];
            int n = in.read(buf);
            if (n <= 0) return true;
            int binary = 0;
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xFF;
                if (b == 0 || (b < 0x09 && b != 0x09) || b == 0x7F) binary++;
            }
            return (double) binary / n > BINARY_RATIO;
        } catch (IOException e) { return true; }
    }

    @Override public Permission permission() { return Permission.READ_ONLY; }
    @Override public boolean requiresApproval() { return false; }
    @Override public Category category() { return Category.FILE; }
    @Override public boolean isDestructive() { return false; }
    @Override public State defaultState() { return State.ENABLED; }
}
