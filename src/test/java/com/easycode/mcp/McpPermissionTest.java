package com.easycode.mcp;

import com.easycode.permission.BlacklistLayer;
import com.easycode.permission.ModeDeductionLayer;
import com.easycode.permission.PermissionContext;
import com.easycode.permission.PermissionMode;
import com.easycode.permission.PermissionResult;
import com.easycode.mcp.McpClient;
import com.easycode.mcp.McpToolAdapter;
import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that MCP tools correctly traverse the permission pipeline:
 * - readOnlyHint=true tools get READ_ONLY permission → ModeDeductionLayer assigns column 0
 * - readOnlyHint=false tools get READ_WRITE permission → ModeDeductionLayer assigns column 2
 * - Blacklist skips MCP tools (no command to match)
 * - Sandbox skips MCP tools (not file tools)
 */
class McpPermissionTest {
    private static final ObjectMapper json = new ObjectMapper();
    private final BlacklistLayer blacklist = new BlacklistLayer();
    private final ModeDeductionLayer modeDeduction = new ModeDeductionLayer();

    @Test
    void shouldTreatReadOnlyMcpToolAsReadOnly() {
        Tool mcpReadOnly = createMcpTool("mcp__demo__search", true);
        ToolCall call = createCall("mcp__demo__search");
        PermissionContext ctx = new PermissionContext(PermissionMode.DEFAULT, null, Path.of("."));

        // Blacklist: should skip (not a command tool)
        assertEquals(PermissionResult.NOT_APPLICABLE,
            blacklist.check(call, mcpReadOnly, ctx));

        // ModeDeduction: should be ALLOW in DEFAULT (read-only column 0)
        assertEquals(PermissionResult.ALLOW,
            modeDeduction.check(call, mcpReadOnly, ctx));
    }

    @Test
    void shouldTreatWriteMcpToolAsExecInDefault() {
        Tool mcpWrite = createMcpTool("mcp__demo__deploy", false);
        ToolCall call = createCall("mcp__demo__deploy");
        PermissionContext ctx = new PermissionContext(PermissionMode.DEFAULT, null, Path.of("."));

        // Blacklist: should skip
        assertEquals(PermissionResult.NOT_APPLICABLE,
            blacklist.check(call, mcpWrite, ctx));

        // ModeDeduction DEFAULT: exec tools → ASK
        assertEquals(PermissionResult.ASK,
            modeDeduction.check(call, mcpWrite, ctx));
    }

    @Test
    void shouldAllowAllMcpToolsInBypassMode() {
        Tool mcpWrite = createMcpTool("mcp__demo__deploy", false);
        Tool mcpRead = createMcpTool("mcp__demo__search", true);
        ToolCall call = createCall("mcp__demo__deploy");
        PermissionContext ctx = new PermissionContext(PermissionMode.BYPASS_PERMISSIONS, null, Path.of("."));

        assertEquals(PermissionResult.ALLOW,
            modeDeduction.check(call, mcpWrite, ctx));
        assertEquals(PermissionResult.ALLOW,
            modeDeduction.check(call, mcpRead, ctx));
    }

    @Test
    void shouldAllowReadOnlyMcpInAllModes() {
        Tool mcpRead = createMcpTool("mcp__demo__read", true);
        ToolCall call = createCall("mcp__demo__read");

        for (PermissionMode mode : PermissionMode.values()) {
            PermissionContext ctx = new PermissionContext(mode, null, Path.of("."));
            assertEquals(PermissionResult.ALLOW,
                modeDeduction.check(call, mcpRead, ctx),
                "Read-only MCP tool should be ALLOW in mode " + mode);
        }
    }

    @Test
    void shouldAskWriteMcpInAcceptEditsMode() {
        Tool mcpWrite = createMcpTool("mcp__demo__write", false);
        ToolCall call = createCall("mcp__demo__write");
        PermissionContext ctx = new PermissionContext(PermissionMode.ACCEPT_EDITS, null, Path.of("."));

        // acceptEdits: file writes → ALLOW, but exec → ASK. MCP write tools are exec category.
        assertEquals(PermissionResult.ASK,
            modeDeduction.check(call, mcpWrite, ctx));
    }

    private static Tool createMcpTool(String name, boolean readOnly) {
        return new McpToolAdapter(
            "demo", name.substring(name.lastIndexOf("__") + 2),
            "Test MCP tool", json.createObjectNode(), readOnly, noopClient()
        );
    }

    private static ToolCall createCall(String toolName) {
        ObjectNode args = json.createObjectNode().put("dummy", "value");
        return new ToolCall("call-id", toolName, args);
    }

    private static McpClient noopClient() {
        return new McpClient(null, "noop") {
            @Override
            CompletableFuture<com.fasterxml.jackson.databind.JsonNode> callTool(String t, com.fasterxml.jackson.databind.JsonNode a) {
                return CompletableFuture.completedFuture(json.createObjectNode());
            }
        };
    }
}
