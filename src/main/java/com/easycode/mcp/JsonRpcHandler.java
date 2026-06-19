package com.easycode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class JsonRpcHandler {
    private static final ObjectMapper json = new ObjectMapper();
    private static final int TIMEOUT_SEC = 30;
    private final AtomicInteger idGen = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final McpTransport transport;

    JsonRpcHandler(McpTransport transport) {
        this.transport = transport;
        if (this.transport != null) {
            this.transport.start(this::onMessage);
        }
    }

    private void onMessage(JsonNode msg) {
        if (!msg.has("id")) return; // notification, ignore
        int id = msg.get("id").asInt();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future != null) future.complete(msg);
    }

    CompletableFuture<JsonNode> call(String method, JsonNode params) {
        if (transport == null) {
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Transport not initialized"));
            return f;
        }
        int id = idGen.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ObjectNode request = json.createObjectNode()
            .put("jsonrpc", "2.0").put("id", id)
            .put("method", method);
        if (params != null) request.set("params", params);
        try {
            transport.send(request);
        } catch (Exception e) {
            pending.remove(id);
            future.completeExceptionally(e);
            return future;
        }
        return future.orTimeout(TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    void close() { transport.close(); }
}
