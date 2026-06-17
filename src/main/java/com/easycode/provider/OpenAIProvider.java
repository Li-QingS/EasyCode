package com.easycode.provider;

import com.easycode.config.Config;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/** OpenAI Chat Completions API 实现 */
public final class OpenAIProvider implements LlmProvider {

    private static final ObjectMapper json = new ObjectMapper();

    private final Config config;
    private final HttpClient httpClient;

    public OpenAIProvider(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void chatStream(List<MessageRecord> history, List<JsonNode> tools, StreamHandler handler) {
        try {
            String requestBody = buildRequestBody(history);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/chat/completions"))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
                    .body()
                    .forEach(line -> parseSSELine(line, handler));

            handler.onComplete();
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    private String buildRequestBody(List<MessageRecord> history) throws JsonProcessingException {
        ObjectNode root = json.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);

        ArrayNode messages = root.putArray("messages");
        for (MessageRecord msg : history) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.role() == Role.USER ? "user" : "assistant");
            m.put("content", msg.content());
        }

        // system prompt 作为第一条消息
        ObjectNode systemMsg = messages.insertObject(0);
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一个有帮助的 AI 助手。");

        return json.writeValueAsString(root);
    }

    private void parseSSELine(String line, StreamHandler handler) {
        if (line == null || line.isBlank()) return;
        if (!line.startsWith("data: ")) return;

        String data = line.substring(6);
        if ("[DONE]".equals(data.trim())) return;

        try {
            JsonNode node = json.readTree(data);
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    JsonNode content = delta.get("content");
                    if (content != null && !content.isNull()) {
                        handler.onToken(content.asText());
                    }
                }
            }
        } catch (JsonProcessingException e) {
            // 忽略无法解析的 SSE 行
        }
    }
}
