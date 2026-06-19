package com.easycode.permission;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ModeDeductionTest {
    private static final ObjectMapper json = new ObjectMapper();
    private final ModeDeductionLayer layer = new ModeDeductionLayer();
    private final PermissionContext ctx = new PermissionContext(PermissionMode.DEFAULT, null, Path.of("."));

    @Test void shouldAllowReadInDefault() {
        assertEquals(PermissionResult.ALLOW, layer.check(call("read_file"), t("read_file"), ctx));
    }
    @Test void shouldAskWriteInDefault() {
        assertEquals(PermissionResult.ASK, layer.check(call("write_file"), t("write_file"), ctx));
    }
    @Test void shouldAllowAllInBypass() {
        var ctx2 = new PermissionContext(PermissionMode.BYPASS_PERMISSIONS, null, Path.of("."));
        assertEquals(PermissionResult.ALLOW, layer.check(call("exec_command"), t("exec_command"), ctx2));
    }
    private ToolCall call(String n) { return new ToolCall("id", n, json.createObjectNode()); }
    private Tool t(String n) { return new Tool() {
        @Override public String name() { return n; } @Override public String description() { return ""; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return json.createObjectNode(); }
        @Override public com.easycode.tool.ToolResult execute(com.fasterxml.jackson.databind.JsonNode i) { return null; }
        @Override public Permission permission() { return Permission.READ_WRITE; }
        @Override public Category category() { return Category.FILE; }
    };}
}
