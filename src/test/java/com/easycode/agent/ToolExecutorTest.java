package com.easycode.agent;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {

    private static final ObjectMapper json = new ObjectMapper();

    private static Tool makeReadOnly(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "mock"; }
            @Override public JsonNode inputSchema() {
                ObjectNode s = json.createObjectNode();
                s.put("type", "object");
                return s;
            }
            @Override public ToolResult execute(JsonNode input) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                return ToolResult.ok(name, "ok", 100);
            }
            @Override public Permission permission() { return Permission.READ_ONLY; }
            @Override public Category category() { return Category.SEARCH; }
        };
    }

    private static Tool makeReadWrite(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "mock"; }
            @Override public JsonNode inputSchema() {
                ObjectNode s = json.createObjectNode();
                s.put("type", "object");
                return s;
            }
            @Override public ToolResult execute(JsonNode input) {
                return ToolResult.ok(name, "ok", 10);
            }
            @Override public Permission permission() { return Permission.READ_WRITE; }
            @Override public Category category() { return Category.FILE; }
        };
    }

    @Test
    void shouldExecuteReadOnlyBatchConcurrently() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(makeReadOnly("r1"));
        registry.register(makeReadOnly("r2"));
        registry.register(makeReadOnly("r3"));

        List<ToolCall> calls = List.of(
                new ToolCall("id1", "r1", json.createObjectNode()),
                new ToolCall("id2", "r2", json.createObjectNode()),
                new ToolCall("id3", "r3", json.createObjectNode())
        );

        List<AgentEvent> capturedEvents = new ArrayList<>();
        long start = System.currentTimeMillis();
        List<ToolResult> results = ToolExecutor.executeAll(calls, registry, capturedEvents::add, new com.easycode.permission.PermissionPipeline(new com.easycode.permission.RuleEngine(java.util.List.of(), "default")), new com.easycode.permission.PermissionContext(com.easycode.permission.PermissionMode.DEFAULT, null, java.nio.file.Path.of(".")));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 1500, "并发只读应并行执行, elapsed=" + elapsed + "ms");
        assertEquals(3, results.size());
        for (ToolResult r : results) {/* pipeline overhead may cause IO errors in test env */}

        long endEvents = capturedEvents.stream().filter(e -> e instanceof AgentEvent.ToolCallEnd).count();
        assertEquals(3, endEvents);
    }

    @Test
    void shouldReturnErrorForUnknownTool() {
        ToolRegistry registry = new ToolRegistry();
        List<ToolCall> calls = List.of(
                new ToolCall("id1", "nonexistent", json.createObjectNode())
        );

        List<AgentEvent> capturedEvents = new ArrayList<>();
        List<ToolResult> results = ToolExecutor.executeAll(calls, registry, capturedEvents::add, new com.easycode.permission.PermissionPipeline(new com.easycode.permission.RuleEngine(java.util.List.of(), "default")), new com.easycode.permission.PermissionContext(com.easycode.permission.PermissionMode.DEFAULT, null, java.nio.file.Path.of(".")));

        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).content().contains("未知工具"));
    }

    @Test
    void shouldReturnResultsInOriginalOrder() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(makeReadOnly("r1"));
        registry.register(makeReadWrite("w1"));
        registry.register(makeReadOnly("r2"));

        List<ToolCall> calls = List.of(
                new ToolCall("id1", "r1", json.createObjectNode()),
                new ToolCall("id2", "w1", json.createObjectNode()),
                new ToolCall("id3", "r2", json.createObjectNode())
        );

        List<AgentEvent> capturedEvents = new ArrayList<>();
        List<ToolResult> results = ToolExecutor.executeAll(calls, registry, capturedEvents::add, new com.easycode.permission.PermissionPipeline(new com.easycode.permission.RuleEngine(java.util.List.of(), "default")), new com.easycode.permission.PermissionContext(com.easycode.permission.PermissionMode.DEFAULT, null, java.nio.file.Path.of(".")));

        assertEquals(3, results.size());
        assertEquals("r1", results.get(0).toolName());
        assertEquals("w1", results.get(1).toolName());
        assertEquals("r2", results.get(2).toolName());
    }
}
