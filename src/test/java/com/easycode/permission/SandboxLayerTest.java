package com.easycode.permission;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SandboxLayerTest {
    private static final ObjectMapper json = new ObjectMapper();
    private final SandboxLayer layer = new SandboxLayer();
    @TempDir Path projectRoot;

    @Test void shouldNotApplicableForExecCommand() {
        var ctx = new PermissionContext(PermissionMode.DEFAULT, null, projectRoot);
        ToolCall call = new ToolCall("id", "exec_command", json.createObjectNode().put("command", "ls"));
        assertEquals(PermissionResult.NOT_APPLICABLE, layer.check(call, t("exec_command"), ctx));
    }
    @Test void shouldDenyOutsidePath() {
        var ctx = new PermissionContext(PermissionMode.DEFAULT, null, projectRoot);
        ToolCall call = new ToolCall("id", "read_file", json.createObjectNode().put("path", "/etc/passwd"));
        assertEquals(PermissionResult.DENY, layer.check(call, t("read_file"), ctx));
    }
    @Test void shouldAllowInsidePath() {
        var ctx = new PermissionContext(PermissionMode.DEFAULT, null, projectRoot);
        ToolCall call = new ToolCall("id", "read_file", json.createObjectNode().put("path", "src/test.txt"));
        assertEquals(PermissionResult.NOT_APPLICABLE, layer.check(call, t("read_file"), ctx));
    }
    private Tool t(String n) { return new Tool() {
        @Override public String name() { return n; } @Override public String description() { return ""; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return json.createObjectNode(); }
        @Override public com.easycode.tool.ToolResult execute(com.fasterxml.jackson.databind.JsonNode i) { return null; }
        @Override public Permission permission() { return Permission.READ_WRITE; }
        @Override public Category category() { return Category.FILE; }
    };}
}
