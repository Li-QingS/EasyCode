package com.easycode.mcp;

import com.easycode.tool.Tool;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public final class McpToolAdapter implements Tool {
    private static final ObjectMapper json = new ObjectMapper();
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final ConcurrentHashMap<String, Boolean> NON_TEXT_WARNED = new ConcurrentHashMap<>();

    private final String fullName, remoteName, description;
    private final JsonNode inputSchema;
    private final boolean readOnly;
    private final McpClient client;

    McpToolAdapter(String serverName, String remoteName, String description,
                   JsonNode inputSchema, boolean readOnly, McpClient client) {
        this.fullName = "mcp__" + serverName + "__" + remoteName;
        this.remoteName = remoteName;
        this.description = (description == null || description.isEmpty())
            ? "来自 MCP server " + serverName + " 的工具 " + remoteName : description;
        this.inputSchema = (inputSchema != null) ? inputSchema : json.createObjectNode().put("type", "object");
        this.readOnly = readOnly;
        this.client = client;
    }

    static boolean isValidName(String fullName) { return VALID_NAME.matcher(fullName).matches(); }

    @Override public String name() { return fullName; }
    @Override public String description() { return description; }
    @Override public JsonNode inputSchema() { return inputSchema; }
    @Override public Category category() { return readOnly ? Category.SEARCH : Category.SHELL; }
    @Override public Permission permission() { return readOnly ? Permission.READ_ONLY : Permission.READ_WRITE; }

    @Override
    public ToolResult execute(JsonNode input) {
        try {
            JsonNode result = client.callTool(remoteName, input != null ? input : json.createObjectNode())
                .get(30, java.util.concurrent.TimeUnit.SECONDS);
            JsonNode resNode = result.get("result");
            boolean isError = resNode != null && resNode.has("isError") && resNode.get("isError").asBoolean();
            StringBuilder sb = new StringBuilder();
            JsonNode content = resNode != null ? resNode.get("content") : null;
            if (content != null && content.isArray()) {
                for (JsonNode c : content) {
                    if ("text".equals(c.has("type") ? c.get("type").asText() : "")) {
                        sb.append(c.get("text").asText()).append('\n');
                    } else {
                        NON_TEXT_WARNED.computeIfAbsent(fullName, k -> {
                            System.err.println("[mcp] warn: tool " + fullName + " returned non-text content blocks (dropped)");
                            return true;
                        });
                    }
                }
            }
            return new ToolResult(fullName, !isError, sb.toString().trim(), 0);
        } catch (TimeoutException e) {
            return ToolResult.err(fullName, "MCP 工具调用超时", 0);
        } catch (Exception e) {
            return ToolResult.err(fullName, "MCP 工具调用失败: " + e.getMessage(), 0);
        }
    }
}
