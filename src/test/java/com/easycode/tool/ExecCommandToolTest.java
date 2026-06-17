package com.easycode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecCommandToolTest {
    private static final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldExecuteSimpleCommand() {
        ObjectNode input = json.createObjectNode();
        input.put("command", "echo hello");
        ToolResult r = new ExecCommandTool().execute(input);
        assertTrue(r.success());
        assertTrue(r.content().contains("hello"));
    }

    @Test
    void shouldBlockDangerousCommand() {
        ObjectNode input = json.createObjectNode();
        input.put("command", "rm -rf /");
        ToolResult r = new ExecCommandTool().execute(input);
        assertFalse(r.success());
        assertTrue(r.content().contains("拦截"));
    }
}
