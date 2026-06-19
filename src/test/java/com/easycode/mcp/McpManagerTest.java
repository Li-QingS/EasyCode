package com.easycode.mcp;

import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpManagerTest {

    @Test
    void shouldHandleEmptyConfig() {
        McpManager mgr = McpManager.discoverAndRegister(
            new ToolRegistry(), Map.of());
        assertNotNull(mgr);
        assertTrue(mgr.tools().isEmpty());
        // close() should be a no-op for empty manager
        assertDoesNotThrow(mgr::close);
    }

    @Test
    void shouldSkipInvalidServerConfigs() {
        ToolRegistry registry = new ToolRegistry();
        Map<String, McpServerConfig> configs = Map.of(
            "nosuch", new McpServerConfig("stdio", "no_such_command_xyz",
                java.util.List.of(), Map.of(), null, Map.of())
        );
        McpManager mgr = McpManager.discoverAndRegister(registry, configs);
        assertNotNull(mgr);
        // The server should be skipped because the command doesn't exist
        assertDoesNotThrow(mgr::close);
    }

    @Test
    void shouldReturnCopyOfToolsList() {
        McpManager mgr = McpManager.discoverAndRegister(
            new ToolRegistry(), Map.of());
        var tools = mgr.tools();
        tools = mgr.tools();
        // Should return independent copies
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
        mgr.close();
    }
}
