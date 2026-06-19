package com.easycode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcHandlerTest {
    private static final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldPairRequestWithResponseById() throws Exception {
        List<JsonNode> sent = new ArrayList<>();
        FakeTransport transport = new FakeTransport(sent);
        JsonRpcHandler handler = new JsonRpcHandler(transport);

        CompletableFuture<JsonNode> future = handler.call("test/method",
            json.createObjectNode().put("key", "value"));

        assertEquals(1, sent.size());
        int id = sent.get(0).get("id").asInt();
        ObjectNode response = json.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", 42);
        transport.deliver(response);

        JsonNode result = future.get(5, TimeUnit.SECONDS);
        assertEquals(42, result.get("result").asInt());
    }

    @Test
    void shouldHandleNullTransportCall() {
        JsonRpcHandler handler = new JsonRpcHandler(null);
        CompletableFuture<JsonNode> future = handler.call("test", json.createObjectNode());
        // With null transport, call should fail immediately
        assertThrows(ExecutionException.class,
            () -> future.get(2, TimeUnit.SECONDS));
    }

    @Test
    void shouldHandleConcurrentRequests() throws Exception {
        List<JsonNode> sent = new ArrayList<>();
        FakeTransport transport = new FakeTransport(sent);
        JsonRpcHandler handler = new JsonRpcHandler(transport);

        CompletableFuture<JsonNode> f1 = handler.call("method", json.createObjectNode());
        CompletableFuture<JsonNode> f2 = handler.call("method", json.createObjectNode());

        assertEquals(2, sent.size());
        assertEquals(1, sent.get(0).get("id").asInt());
        assertEquals(2, sent.get(1).get("id").asInt());

        // Deliver responses in reverse order to prove id-based pairing
        transport.deliver(json.createObjectNode()
            .put("jsonrpc", "2.0").put("id", 2).put("result", "second"));
        transport.deliver(json.createObjectNode()
            .put("jsonrpc", "2.0").put("id", 1).put("result", "first"));

        assertEquals("second", f2.get(5, TimeUnit.SECONDS).get("result").asText());
        assertEquals("first", f1.get(5, TimeUnit.SECONDS).get("result").asText());
    }

    private static class FakeTransport implements McpTransport {
        private final List<JsonNode> sent;
        private Consumer<JsonNode> onMessage;

        FakeTransport(List<JsonNode> sent) { this.sent = sent; }

        @Override public void start(Consumer<JsonNode> onMessage) { this.onMessage = onMessage; }
        @Override public void send(JsonNode message) { sent.add(message); }
        @Override public void close() {}
        void deliver(JsonNode msg) { onMessage.accept(msg); }
    }
}
