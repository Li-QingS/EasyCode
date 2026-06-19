package com.easycode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

interface McpTransport {
    void start(Consumer<JsonNode> onMessage);
    void send(JsonNode message);
    void close();
}
