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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

public final class OpenAIProvider implements LlmProvider {

    private static final ObjectMapper json = new ObjectMapper();
    private final Config config;
    private final HttpClient httpClient;

    public OpenAIProvider(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void chatStream(Request req, StreamHandler handler) {
        try {
            String requestBody = buildRequestBody(req.messages(), req.tools(), req.system(), req.reminder());
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/chat/completions"))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            httpClient.sendAsync(httpReq, java.net.http.HttpResponse.BodyHandlers.fromLineSubscriber(
                new Flow.Subscriber<>() {
                    private Flow.Subscription sub;
                    private final Map<Integer, ToolCallAccumulator> accumulators = new ConcurrentHashMap<>();
                    public void onSubscribe(Flow.Subscription s) { (sub = s).request(Long.MAX_VALUE); }
                    public void onNext(String line) { parseSSELine(line, handler, accumulators); sub.request(1); }
                    public void onError(Throwable t) { handler.onError(new Exception(t)); }
                    public void onComplete() { handler.onComplete(); }
                }
            )).join();
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    private String buildRequestBody(List<MessageRecord> history, List<JsonNode> tools,
            com.easycode.provider.System sys, String reminder) throws JsonProcessingException {
        ObjectNode root = json.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        String sysContent = (sys.stable() != null ? sys.stable() : config.systemPromptSafe());
        if (sys.environment() != null && !sys.environment().isEmpty())
            sysContent += "\n\n" + sys.environment();
        systemMsg.put("content", sysContent);

        for (MessageRecord msg : history) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.role() == Role.USER ? "user" : "assistant");
            if (!msg.blocks().isEmpty()) {
                boolean hasToolBlocks = false;
                for (MessageBlock block : msg.blocks()) {
                    if (block instanceof MessageBlock.ToolUseBlock tu) {
                        hasToolBlocks = true;
                        ArrayNode toolCalls = m.has("tool_calls") ? (ArrayNode) m.get("tool_calls") : m.putArray("tool_calls");
                        ObjectNode tc = toolCalls.addObject();
                        tc.put("id", tu.id()); tc.put("type", "function");
                        ObjectNode func = tc.putObject("function");
                        func.put("name", tu.name()); func.put("arguments", tu.input().toString());
                    } else if (block instanceof MessageBlock.ToolResultBlock tr) {
                        m.put("role", "tool"); m.put("tool_call_id", tr.toolUseId()); m.put("content", tr.content());
                        hasToolBlocks = true;
                    } else if (block instanceof MessageBlock.TextBlock tb) {
                        if (hasToolBlocks) { if (!m.has("content") || m.get("content").isNull()) m.put("content", tb.text()); }
                        else m.put("content", tb.text());
                    }
                }
            } else if (msg.content() != null && !msg.content().isEmpty()) {
                m.put("content", msg.content());
            }
        }

        if (reminder != null && !reminder.isEmpty()) {
            ObjectNode remMsg = messages.addObject();
            remMsg.put("role", "user"); remMsg.put("content", reminder);
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArr = root.putArray("tools");
            for (JsonNode t : tools) {
                ObjectNode toolDef = toolsArr.addObject();
                toolDef.put("type", "function");
                ObjectNode func = toolDef.putObject("function");
                func.put("name", t.get("name").asText());
                func.put("description", t.get("description").asText());
                func.set("parameters", t.get("input_schema"));
            }
            root.put("tool_choice", "auto");
        }
        return json.writeValueAsString(root);
    }

    private static class ToolCallAccumulator {
        String id; String name; StringBuilder arguments = new StringBuilder();
    }

    private void parseSSELine(String line, StreamHandler handler,
            Map<Integer, ToolCallAccumulator> accumulators) {
        if (line == null || line.isBlank() || !line.startsWith("data: ")) return;
        String data = line.substring(6);
        if ("[DONE]".equals(data.trim())) return;
        try {
            JsonNode node = json.readTree(data);
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    JsonNode content = delta.get("content");
                    if (content != null && !content.isNull()) handler.onToken(content.asText());
                    JsonNode toolCalls = delta.get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            int idx = tc.has("index") ? tc.get("index").asInt() : 0;
                            ToolCallAccumulator acc = accumulators.computeIfAbsent(idx, k -> new ToolCallAccumulator());
                            if (tc.has("id") && !tc.get("id").isNull()) acc.id = tc.get("id").asText();
                            JsonNode func = tc.get("function");
                            if (func != null) {
                                if (func.has("name") && !func.get("name").isNull()) acc.name = func.get("name").asText();
                                JsonNode args = func.get("arguments");
                                if (args != null && !args.isNull()) acc.arguments.append(args.asText());
                            }
                            String argsStr = acc.arguments.toString();
                            if (!argsStr.isEmpty() && argsStr.endsWith("}") && acc.id != null && acc.name != null) {
                                try {
                                    handler.onToolCall(new ToolCall(acc.id, acc.name, json.readTree(argsStr)));
                                    accumulators.remove(idx);
                                } catch (JsonProcessingException e) {}
                            }
                        }
                    }
                }
            }
            JsonNode usage = node.get("usage");
            if (usage != null) {
                int in = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                int out = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                int cacheRead = 0;
                JsonNode details = usage.get("prompt_tokens_details");
                if (details != null && details.has("cached_tokens"))
                    cacheRead = details.get("cached_tokens").asInt();
                if (in > 0 || out > 0) handler.onUsage(in, out, 0, cacheRead);
            }
        } catch (JsonProcessingException e) {}
    }
}
