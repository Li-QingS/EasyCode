package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test
    void shouldRegisterAndGet() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        assertNotNull(registry.get("read_file"));
        assertEquals(1, registry.size());
    }

    @Test
    void shouldThrowForUnknownTool() {
        ToolRegistry registry = new ToolRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.get("nonexistent"));
    }

    @Test
    void shouldGenerateToolsJson() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        List<JsonNode> tools = registry.toToolsJson();
        assertEquals(2, tools.size());
        assertEquals("read_file", tools.get(0).get("name").asText());
        assertNotNull(tools.get(0).get("description"));
        assertNotNull(tools.get(0).get("input_schema"));
    }
}
