package com.easycode.mcp;

import com.easycode.tool.Tool;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class McpToolAdapterTest {
    private static final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldBuildValidName() {
        McpToolAdapter adapter = new McpToolAdapter("github", "create_issue",
            "Create an issue", json.createObjectNode(), true, noopClient());
        assertEquals("mcp__github__create_issue", adapter.name());
        assertEquals(Tool.Category.SEARCH, adapter.category());
        assertEquals(Tool.Permission.READ_ONLY, adapter.permission());
    }

    @Test
    void shouldRejectInvalidNameCharacters() {
        assertFalse(McpToolAdapter.isValidName("mcp__github__tool.name"));
        assertFalse(McpToolAdapter.isValidName("mcp__github__tool@name"));
        assertTrue(McpToolAdapter.isValidName("mcp__github__tool_name"));
        assertTrue(McpToolAdapter.isValidName("mcp__server__tool123"));
    }

    @Test
    void shouldUseFallbackDescription() {
        McpToolAdapter adapter = new McpToolAdapter("demo", "echo",
            "", json.createObjectNode(), false, noopClient());
        assertTrue(adapter.description().contains("demo"));
        assertTrue(adapter.description().contains("echo"));
    }

    @Test
    void shouldDefaultInputSchemaWhenNull() {
        McpToolAdapter adapter = new McpToolAdapter("demo", "echo",
            "desc", null, false, noopClient());
        assertEquals("object", adapter.inputSchema().get("type").asText());
    }

    @Test
    void shouldSetReadWriteForNonReadOnly() {
        McpToolAdapter adapter = new McpToolAdapter("demo", "tool",
            "desc", json.createObjectNode(), false, noopClient());
        assertEquals(Tool.Permission.READ_WRITE, adapter.permission());
        assertEquals(Tool.Category.SHELL, adapter.category());
    }

    @Test
    void shouldExecuteSuccessfully() {
        JsonNode response = createToolResponse("Hello world", false);
        McpClient stub = stubForJson(response);

        McpToolAdapter adapter = new McpToolAdapter("demo", "echo",
            "Say hello", json.createObjectNode(), false, stub);

        ToolResult result = adapter.execute(json.createObjectNode().put("message", "hello"));
        assertTrue(result.success());
        assertEquals("Hello world", result.content());
    }

    @Test
    void shouldMapRemoteError() {
        JsonNode response = createToolResponse("Something went wrong", true);
        McpClient stub = stubForJson(response);

        McpToolAdapter adapter = new McpToolAdapter("demo", "failing",
            "A tool that fails", json.createObjectNode(), false, stub);

        ToolResult result = adapter.execute(json.createObjectNode());
        assertFalse(result.success());
    }

    @Test
    void shouldHandleMultipleTextBlocks() {
        ObjectNode resp = json.createObjectNode();
        ObjectNode resultNode = json.createObjectNode();
        ArrayNode content = json.createArrayNode();
        content.addObject().put("type", "text").put("text", "First");
        content.addObject().put("type", "text").put("text", "Second");
        resultNode.set("content", content);
        resp.set("result", resultNode);

        McpClient stub = stubForJson(resp);
        McpToolAdapter adapter = new McpToolAdapter("demo", "multi",
            "desc", json.createObjectNode(), false, stub);

        ToolResult result = adapter.execute(json.createObjectNode());
        assertTrue(result.success());
        assertTrue(result.content().contains("First"));
        assertTrue(result.content().contains("Second"));
    }

    @Test
    void shouldMapExceptionToError() {
        CompletableFuture<JsonNode> fault = new CompletableFuture<>();
        fault.completeExceptionally(new RuntimeException("Connection lost"));
        McpClient stub = stubForFuture(fault);

        McpToolAdapter adapter = new McpToolAdapter("demo", "broken",
            "desc", json.createObjectNode(), false, stub);

        ToolResult result = adapter.execute(json.createObjectNode());
        assertFalse(result.success());
        assertTrue(result.content().contains("失败") || result.content().contains("Connection"));
    }

    private JsonNode createToolResponse(String text, boolean isError) {
        ObjectNode resp = json.createObjectNode();
        ObjectNode resultNode = json.createObjectNode();
        if (isError) resultNode.put("isError", true);
        ArrayNode content = json.createArrayNode();
        content.addObject().put("type", "text").put("text", text);
        resultNode.set("content", content);
        resp.set("result", resultNode);
        return resp;
    }

    private static McpClient stubForJson(JsonNode response) {
        return stubForFuture(CompletableFuture.completedFuture(response));
    }

    private static McpClient stubForFuture(CompletableFuture<JsonNode> future) {
        return new McpClient(null, "test") {
            @Override
            CompletableFuture<JsonNode> callTool(String tool, JsonNode args) {
                return future;
            }
        };
    }

    private static McpClient noopClient() {
        return new McpClient(null, "noop") {
            @Override
            CompletableFuture<JsonNode> callTool(String tool, JsonNode args) {
                return CompletableFuture.completedFuture(json.createObjectNode());
            }
        };
    }
}
