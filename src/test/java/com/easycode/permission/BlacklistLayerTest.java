package com.easycode.permission;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BlacklistLayerTest {
    private static final ObjectMapper json = new ObjectMapper();
    private final BlacklistLayer layer = new BlacklistLayer();
    private final PermissionContext ctx = new PermissionContext(PermissionMode.DEFAULT, null, Path.of("."));

    @Test void shouldDenyRmRfRoot() {
        assertEquals(PermissionResult.DENY, layer.check(callE("rm -rf /"), toolE(), ctx));
    }
    @Test void shouldDenyMkfs() {
        assertEquals(PermissionResult.DENY, layer.check(callE("mkfs.ext4 /dev/sda"), toolE(), ctx));
    }
    @Test void shouldAllowNormalCommand() {
        assertEquals(PermissionResult.NOT_APPLICABLE, layer.check(callE("ls -la"), toolE(), ctx));
    }
    @Test void shouldNotApplicableForFileTools() {
        assertEquals(PermissionResult.NOT_APPLICABLE, layer.check(callF("src/test.txt"), toolF(), ctx));
    }

    private ToolCall callE(String cmd) { return new ToolCall("id", "exec_command", json.createObjectNode().put("command", cmd)); }
    private ToolCall callF(String path) { return new ToolCall("id", "read_file", json.createObjectNode().put("path", path)); }
    private Tool toolE() { return tool("exec_command"); }
    private Tool toolF() { return tool("read_file"); }
    private Tool tool(String n) { return new Tool() {
        @Override public String name() { return n; }
        @Override public String description() { return ""; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return json.createObjectNode(); }
        @Override public com.easycode.tool.ToolResult execute(com.fasterxml.jackson.databind.JsonNode i) { return null; }
        @Override public Permission permission() { return Permission.READ_WRITE; }
        @Override public Category category() { return Category.FILE; }
    };}
}
