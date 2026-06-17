package com.easycode.provider;

import com.easycode.config.Config;
import com.easycode.conversation.MessageBlock;
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
import java.util.List;
import java.util.concurrent.Flow;

public final class AnthropicProvider implements LlmProvider {

    private static final ObjectMapper json = new ObjectMapper();
    private static final boolean DEBUG_SSE = false;

    private final Config config;
    private final HttpClient httpClient;
    private StringBuilder toolCallBuilder;
    private String toolCallId;
    private String toolCallName;

    public AnthropicProvider(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void chatStream(Request req, StreamHandler handler) {
        try {
            String requestBody = buildRequestJson(req);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/messages"))
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            httpClient.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.fromLineSubscriber(
                new Flow.Subscriber<>() {
                    private Flow.Subscription sub;
                    public void onSubscribe(Flow.Subscription s) { (sub = s).request(Long.MAX_VALUE); }
                    public void onNext(String line) { parseSSELine(line, handler); sub.request(1); }
                    public void onError(Throwable t) { handler.onError(new Exception(t)); }
                    public void onComplete() { handler.onComplete(); }
                }
            )).join();
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    private String buildRequestJson(Request req) throws JsonProcessingException {
        ObjectNode root = json.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        root.put("max_tokens", 8192);

        // system: 数组格式，第一个块带 cache_control
        com.easycode.provider.System sys = req.system();
        ArrayNode systemArr = root.putArray("system");
        if (sys.stable() != null && !sys.stable().isEmpty()) {
            ObjectNode stableBlock = systemArr.addObject();
            stableBlock.put("type", "text");
            stableBlock.put("text", sys.stable());
            stableBlock.putObject("cache_control").put("type", "ephemeral");
        }
        if (sys.environment() != null && !sys.environment().isEmpty()) {
            ObjectNode envBlock = systemArr.addObject();
            envBlock.put("type", "text");
            envBlock.put("text", sys.environment());
        }

        // tools
        if (req.tools() != null && !req.tools().isEmpty()) {
            ArrayNode toolsArr = root.putArray("tools");
            req.tools().forEach(toolsArr::add);
        }

        // messages
        ArrayNode messages = root.putArray("messages");
        List<MessageRecord> history = req.messages();
        for (int i = 0; i < history.size(); i++) {
            MessageRecord msg = history.get(i);
            ObjectNode m = messages.addObject();
            m.put("role", msg.role() == Role.USER ? "user" : "assistant");
            if (!msg.blocks().isEmpty()) {
                ArrayNode content = m.putArray("content");
                for (MessageBlock block : msg.blocks()) {
                    serializeBlock(content, block);
                }
                // reminder 注入：如果是最后一条 user 消息且有 reminder
                if (i == history.size() - 1 && msg.role() == Role.USER
                        && req.reminder() != null && !req.reminder().isEmpty()) {
                    ObjectNode remBlock = content.addObject();
                    remBlock.put("type", "text");
                    remBlock.put("text", req.reminder());
                }
            } else if (msg.content() != null && !msg.content().isEmpty()) {
                ArrayNode content = m.putArray("content");
                ObjectNode textBlock = content.addObject();
                textBlock.put("type", "text");
                textBlock.put("text", msg.content());
                // reminder 注入：如果是最后一条 user 消息
                if (i == history.size() - 1 && msg.role() == Role.USER
                        && req.reminder() != null && !req.reminder().isEmpty()) {
                    ObjectNode remBlock = content.addObject();
                    remBlock.put("type", "text");
                    remBlock.put("text", req.reminder());
                }
            }
        }

        root.putObject("thinking").put("type", "enabled").put("budget_tokens", 4000);
        return json.writeValueAsString(root);
    }

    private void serializeBlock(ArrayNode content, MessageBlock block) {
        if (block instanceof MessageBlock.TextBlock tb) {
            ObjectNode o = content.addObject();
            o.put("type", "text");
            o.put("text", tb.text());
        } else if (block instanceof MessageBlock.ToolUseBlock tu) {
            ObjectNode o = content.addObject();
            o.put("type", "tool_use");
            o.put("id", tu.id());
            o.put("name", tu.name());
            o.set("input", tu.input());
        } else if (block instanceof MessageBlock.ToolResultBlock tr) {
            ObjectNode o = content.addObject();
            o.put("type", "tool_result");
            o.put("tool_use_id", tr.toolUseId());
            o.put("content", tr.content());
            if (tr.isError()) o.put("is_error", true);
        }
    }

    private void parseSSELine(String line, StreamHandler handler) {
        if (line == null || line.isBlank() || !line.startsWith("data: ")) return;
        if (DEBUG_SSE) java.lang.System.err.println("[SSE] " + line.substring(0, Math.min(line.length(), 200)));
        String data = line.substring(6);
        try {
            JsonNode node = json.readTree(data);
            String type = node.has("type") ? node.get("type").asText() : "";

        if ("content_block_delta".equals(type)) {
            JsonNode delta = node.get("delta");
            if (delta == null) return;
            String dt = delta.has("type") ? delta.get("type").asText() : "";
            if ("text_delta".equals(dt)) {
                JsonNode t = delta.get("text");
                if (t != null && !t.isNull()) handler.onToken(t.asText());
            } else if ("thinking_delta".equals(dt)) {
                JsonNode th = delta.get("thinking");
                if (th != null && !th.isNull()) handler.onThinking(th.asText());
            } else if ("input_json_delta".equals(dt)) {
                JsonNode p = delta.get("partial_json");
                if (p != null && toolCallBuilder != null) toolCallBuilder.append(p.asText());
            }
        } else if ("content_block_start".equals(type)) {
            JsonNode block = node.get("content_block");
            if (block != null && "tool_use".equals(block.has("type") ? block.get("type").asText() : "")) {
                toolCallBuilder = new StringBuilder();
                toolCallId = block.get("id").asText();
                toolCallName = block.get("name").asText();
            }
        } else if ("content_block_stop".equals(type)) {
            if (toolCallBuilder != null && toolCallBuilder.length() > 0) {
                try {
                    JsonNode input = json.readTree(toolCallBuilder.toString());
                    handler.onToolCall(new ToolCall(toolCallId, toolCallName, input));
                } catch (JsonProcessingException e) {
                    handler.onError(new Exception("工具参数解析失败: " + e.getMessage(), e));
                }
                toolCallBuilder = null; toolCallId = null; toolCallName = null;
            }
        } else if ("message_delta".equals(type)) {
            JsonNode usage = node.get("usage");
            if (usage != null) {
                int in = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                int out = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                int cacheWrite = usage.has("cache_creation_input_tokens") ? usage.get("cache_creation_input_tokens").asInt() : 0;
                int cacheRead = usage.has("cache_read_input_tokens") ? usage.get("cache_read_input_tokens").asInt() : 0;
                handler.onUsage(in, out, cacheWrite, cacheRead);
            }
        }
        } catch (JsonProcessingException ignored) {}
    }
}
