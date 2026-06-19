package com.easycode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class McpClient {
    private static final ObjectMapper json = new ObjectMapper();
    private final JsonRpcHandler rpc;
    private final String serverName;

    McpClient(McpTransport transport, String serverName) {
        this.rpc = new JsonRpcHandler(transport);
        this.serverName = serverName;
    }

    CompletableFuture<Void> initialize() {
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities").putObject("tools");
        ObjectNode ci = params.putObject("clientInfo");
        ci.put("name", "EasyCode"); ci.put("version", "1.0.0");
        return rpc.call("initialize", params).thenAccept(r -> {});
    }

    CompletableFuture<List<ToolDef>> listTools() {
        return rpc.call("tools/list", null).thenApply(resp -> {
            List<ToolDef> tools = new ArrayList<>();
            JsonNode arr = resp.get("result").get("tools");
            if (arr != null && arr.isArray()) {
                for (JsonNode t : arr) {
                    String name = t.get("name").asText();
                    String desc = t.has("description") ? t.get("description").asText() : "";
                    JsonNode schema = t.get("inputSchema");
                    boolean readOnly = false;
                    JsonNode ann = t.get("annotations");
                    if (ann != null && ann.has("readOnlyHint"))
                        readOnly = ann.get("readOnlyHint").asBoolean();
                    tools.add(new ToolDef(name, desc, schema, readOnly));
                }
            }
            return tools;
        });
    }

    CompletableFuture<JsonNode> callTool(String toolName, JsonNode arguments) {
        ObjectNode params = json.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        return rpc.call("tools/call", params);
    }

    String serverName() { return serverName; }
    void close() { rpc.close(); }

    record ToolDef(String name, String description, JsonNode inputSchema, boolean readOnlyHint) {}
}
