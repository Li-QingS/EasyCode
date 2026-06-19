package com.easycode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.Consumer;

final class HttpTransport implements McpTransport {
    private static final ObjectMapper json = new ObjectMapper();
    private final String url;
    private final Map<String, String> headers;
    private final HttpClient httpClient;
    private Consumer<JsonNode> onMessage;

    HttpTransport(String url, Map<String, String> headers) {
        this.url = url; this.headers = headers;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void start(Consumer<JsonNode> onMessage) { this.onMessage = onMessage; }

    @Override
    public void send(JsonNode message) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.toString()));
            if (headers != null) headers.forEach(rb::header);
            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
                onMessage.accept(json.readTree(resp.body()));
            }
        } catch (Exception e) { /* error handled by caller via timeout */ }
    }

    @Override
    public void close() { /* no persistent connection to close */ }
}
