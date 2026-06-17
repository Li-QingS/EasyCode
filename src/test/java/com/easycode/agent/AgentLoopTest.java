package com.easycode.agent;

import com.easycode.config.Config;
import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.provider.LlmProvider;
import com.easycode.provider.Request;
import com.easycode.provider.StreamHandler;
import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    private static final ObjectMapper json = new ObjectMapper();

    private static Config testConfig() {
        return new Config("anthropic", "claude-test", "http://localhost", "test-key");
    }

    // 返回纯文本的 mock provider
    private static class SingleTextProvider implements LlmProvider {
        private final String text;
        SingleTextProvider(String text) { this.text = text; }
        @Override
        public void chatStream(Request req, StreamHandler handler) {
            handler.onToken(text);
            handler.onComplete();
        }
    }

    // 返回工具调用 + 纯文本的 mock provider
    private static class ToolCallProvider implements LlmProvider {
        private final List<ToolCall> calls;
        private final String finalText;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final int maxCalls;

        ToolCallProvider(List<ToolCall> calls, String finalText, int maxCalls) {
            this.calls = calls;
            this.finalText = finalText;
            this.maxCalls = maxCalls;
        }

        @Override
        public void chatStream(Request req, StreamHandler handler) {
            int n = callCount.incrementAndGet();
            if (n <= maxCalls) {
                for (ToolCall tc : calls) handler.onToolCall(tc);
            } else {
                handler.onToken(finalText);
            }
            handler.onUsage(10, 5, 0, 0);
            handler.onComplete();
        }
    }

    // 抛出异常的 mock provider
    private static class ErrorProvider implements LlmProvider {
        @Override
        public void chatStream(Request req, StreamHandler handler) {
            handler.onError(new RuntimeException("stream error"));
        }
    }

    @Test
    void shouldCompleteForTextOnlyResponse() {
        var provider = new SingleTextProvider("你好");
        ToolRegistry registry = new ToolRegistry();
        ConversationMgr conv = new ConversationMgr();
        AgentLoop loop = new AgentLoop(provider, registry, conv, testConfig(), "test");

        List<AgentEvent> events = new ArrayList<>();
        String result = loop.run("hello", events::add);

        assertEquals("你好", result);
        // 应该有 AgentFinished 事件
        boolean hasFinished = events.stream().anyMatch(e -> e instanceof AgentEvent.AgentFinished);
        assertTrue(hasFinished);
    }

    @Test
    void shouldExecuteToolCallsAndContinue() {
        // 注册 mock 工具
        Tool mockTool = new Tool() {
            @Override public String name() { return "mock_read"; }
            @Override public String description() { return "mock"; }
            @Override public JsonNode inputSchema() {
                ObjectNode s = json.createObjectNode(); s.put("type", "object"); return s;
            }
            @Override public ToolResult execute(JsonNode input) {
                return ToolResult.ok("mock_read", "result", 10);
            }
            @Override public Permission permission() { return Permission.READ_ONLY; }
            @Override public Category category() { return Category.FILE; }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(mockTool);

        ToolCall tc = new ToolCall("tcid", "mock_read", json.createObjectNode());
        var provider = new ToolCallProvider(List.of(tc), "最终答复", 1);
        ConversationMgr conv = new ConversationMgr();
        AgentLoop loop = new AgentLoop(provider, registry, conv, testConfig(), "test");

        List<AgentEvent> events = new ArrayList<>();
        String result = loop.run("do it", events::add);

        assertEquals("最终答复", result);
        // 验证历史增长（user msg + 2x assistant[tool_use] + 2x tool_result[USER] + final assistant）
        List<MessageRecord> history = conv.getHistory();
        assertTrue(history.size() >= 3, "历史应有至少 3 条消息, 实际: " + history.size());
    }

    @Test
    void shouldStopForUnknownTools() {
        ToolRegistry registry = new ToolRegistry();
        ToolCall tc = new ToolCall("tcid", "unknown_tool", json.createObjectNode());
        var provider = new AlwaysToolProvider(tc);
        ConversationMgr conv = new ConversationMgr();
        AgentLoop loop = new AgentLoop(provider, registry, conv, testConfig(), "test");

        List<AgentEvent> events = new ArrayList<>();
        String result = loop.run("do it", events::add);

        assertNull(result, "连续未知工具应返回 null");
        boolean hasError = events.stream().anyMatch(e ->
                e instanceof AgentEvent.Error err && err.message().contains("未知工具"));
        assertTrue(hasError, "应有未知工具错误事件");
    }

    // 永远返回同一个工具调用的 provider（用于触发未知工具停止）
    private static class AlwaysToolProvider implements LlmProvider {
        private final ToolCall tc;
        AlwaysToolProvider(ToolCall tc) { this.tc = tc; }
        @Override
        public void chatStream(Request req, StreamHandler handler) {
            handler.onToolCall(tc);
            handler.onUsage(10, 5, 0, 0);
            handler.onComplete();
        }
    }

    @Test
    void shouldStopOnStreamError() {
        var provider = new ErrorProvider();
        ToolRegistry registry = new ToolRegistry();
        ConversationMgr conv = new ConversationMgr();
        AgentLoop loop = new AgentLoop(provider, registry, conv, testConfig(), "test");

        List<AgentEvent> events = new ArrayList<>();
        String result = loop.run("do it", events::add);

        assertNull(result);
        boolean hasError = events.stream().anyMatch(e -> e instanceof AgentEvent.Error);
        assertTrue(hasError);
    }

    @Test
    void shouldFilterToolsInPlanMode() {
        Tool readOnlyTool = new Tool() {
            @Override public String name() { return "read_only"; }
            @Override public String description() { return "mock"; }
            @Override public JsonNode inputSchema() {
                ObjectNode s = json.createObjectNode(); s.put("type", "object"); return s;
            }
            @Override public ToolResult execute(JsonNode input) {
                return ToolResult.ok("read_only", "ok", 0);
            }
            @Override public Permission permission() { return Permission.READ_ONLY; }
            @Override public Category category() { return Category.FILE; }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(readOnlyTool);

        // 验证 planMode 过滤后的工具列表
        List<JsonNode> planTools = registry.toToolsJson(Tool.Permission.READ_ONLY);
        assertEquals(1, planTools.size());
        assertEquals("read_only", planTools.get(0).get("name").asText());

        List<JsonNode> allTools = registry.toToolsJson();
        assertEquals(1, allTools.size());
    }
}
