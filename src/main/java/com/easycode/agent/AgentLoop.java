package com.easycode.agent;

import com.easycode.config.Config;
import com.easycode.context.ContextManager;
import com.easycode.context.CompressEvent;
import com.easycode.context.SessionManager;
import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.permission.PermissionConfig;
import com.easycode.permission.PermissionContext;
import com.easycode.permission.PermissionMode;
import com.easycode.permission.PermissionPipeline;
import com.easycode.prompt.Environment;
import com.easycode.prompt.Prompt;
import com.easycode.prompt.Reminder;
import com.easycode.provider.LlmProvider;
import com.easycode.provider.Request;
import com.easycode.provider.System;
import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class AgentLoop {
    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_CONSECUTIVE_UNKNOWN = 3;
    private static final int MAX_EMPTY_TEXT_RETRIES = 3;
    private final LlmProvider provider;
    private final ToolRegistry tools;
    private final ConversationMgr conversation;
    private final Config config;
    private final String appVersion;
    private volatile boolean cancelled;
    private boolean planMode;
    private PermissionMode permMode = PermissionMode.DEFAULT;
    private final ContextManager contextManager;
    private int totalInputTokens;
    private int totalOutputTokens;
    private int emptyTextRetries;

    public AgentLoop(LlmProvider provider, ToolRegistry tools,
                     ConversationMgr conversation, Config config, String appVersion) {
        this.provider = provider;
        this.tools = tools;
        this.conversation = conversation;
        this.config = config;
        this.appVersion = appVersion;
        this.contextManager = new ContextManager(provider, config, SessionManager.sessionId());
    }

    public String run(String userMessage, Consumer<AgentEvent> eventSink) {
        cancelled = false;
        int consecutiveUnknownTools = 0;
        emptyTextRetries = 0;
        conversation.addUserMessage(userMessage);
        Environment env = Environment.collect(appVersion, config.model());
        String stablePrompt = Prompt.buildStable();
        PermissionPipeline pipeline = new PermissionPipeline(PermissionConfig.load(Path.of("").toAbsolutePath()));
        if (permMode == PermissionMode.DEFAULT) permMode = pipeline.startMode();
        for (int round = 1; round <= MAX_ITERATIONS; round++) {
            if (cancelled) {
                eventSink.accept(new AgentEvent.Error("已取消", false));
                eventSink.accept(new AgentEvent.AgentFinished(null, round - 1, totalInputTokens, totalOutputTokens));
                return null;
            }
            eventSink.accept(new AgentEvent.IterationProgress(round, MAX_ITERATIONS));
            List<JsonNode> toolsJson = planMode
                    ? tools.toToolsJson(Tool.Permission.READ_ONLY)
                    : tools.toToolsJson();
            String reminder = "";
            if (planMode) {
                reminder = Reminder.planReminder(Reminder.isFullReminder(round));
            }
            // ch08: 上下文管理
            CompressEvent autoEvt = contextManager.autoManage(this.conversation, toolsJson);
            if (autoEvt.replacedCount() > 0 || !autoEvt.success()) {
                eventSink.accept(new AgentEvent.ContextCompress(autoEvt));
            }
            contextManager.markRequested(conversation.getHistory().size());
            Request req = new Request(conversation.getHistory(), toolsJson,
                    new System(stablePrompt, env.render()), reminder);
            StreamingCollector collector = new StreamingCollector(eventSink);
            try {
                provider.chatStream(req, collector);
            } catch (Exception e) {
                eventSink.accept(new AgentEvent.Error("Provider error: " + e.getMessage(), false));
                eventSink.accept(new AgentEvent.AgentFinished(null, round, totalInputTokens, totalOutputTokens));
                return null;
            }
            if (collector.hasError()) {
                eventSink.accept(new AgentEvent.Error(collector.getErrorMessage(), false));
                eventSink.accept(new AgentEvent.AgentFinished(null, round, totalInputTokens, totalOutputTokens));
                return null;
            }
            int roundIn = collector.getRoundInputTokens();
            int roundOut = collector.getRoundOutputTokens();
            contextManager.updateUsage(roundIn, collector.getCacheReadTokens(), 0, roundOut);
            totalInputTokens += roundIn;
            totalOutputTokens += roundOut;
            eventSink.accept(new AgentEvent.TokenUsage(roundIn, roundOut, totalInputTokens, totalOutputTokens,
                    collector.getCacheWriteTokens(), collector.getCacheReadTokens()));
            List<ToolCall> toolCalls = collector.getToolCalls();
            String finalText = collector.getFullText();
            if (toolCalls.isEmpty() && !finalText.isBlank()) {
                emptyTextRetries = 0;
                conversation.addAssistantMessage(finalText);
                eventSink.accept(new AgentEvent.AgentFinished(finalText, round, totalInputTokens, totalOutputTokens));
                return finalText;
            }
            String thinkingText = collector.getThinkingText();
            if (toolCalls.isEmpty() && !thinkingText.isBlank()) {
                emptyTextRetries = 0;
                conversation.addAssistantMessage(thinkingText);
                eventSink.accept(new AgentEvent.AgentFinished(thinkingText, round, totalInputTokens, totalOutputTokens));
                return thinkingText;
            }
            if (toolCalls.isEmpty()) {
                emptyTextRetries++;
                if (emptyTextRetries >= MAX_EMPTY_TEXT_RETRIES) {
                    eventSink.accept(new AgentEvent.Error("连续 " + MAX_EMPTY_TEXT_RETRIES + " 轮无文本输出，已停止", false));
                    eventSink.accept(new AgentEvent.AgentFinished(null, round, totalInputTokens, totalOutputTokens));
                    return null;
                }
                continue;
            }
            emptyTextRetries = 0;
            boolean allUnknown = true;
            for (ToolCall tc : toolCalls) {
                try { tools.get(tc.name()); allUnknown = false; }
                catch (IllegalArgumentException e) {}
            }
            if (allUnknown) {
                consecutiveUnknownTools++;
                if (consecutiveUnknownTools >= MAX_CONSECUTIVE_UNKNOWN) {
                    eventSink.accept(new AgentEvent.Error("连续 " + MAX_CONSECUTIVE_UNKNOWN + " 轮请求未知工具，已停止", false));
                    List<MessageBlock> ub = new ArrayList<>();
                    List<MessageBlock> rb = new ArrayList<>();
                    for (ToolCall tc : toolCalls) {
                        ub.add(new MessageBlock.ToolUseBlock(tc.id(), tc.name(), tc.input()));
                        rb.add(new MessageBlock.ToolResultBlock(tc.id(), "未知工具: " + tc.name(), true));
                    }
                    conversation.addMessage(new MessageRecord(Role.ASSISTANT, "", ub));
                    conversation.addMessage(new MessageRecord(Role.USER, "", rb));
                    eventSink.accept(new AgentEvent.AgentFinished(null, round, totalInputTokens, totalOutputTokens));
                    return null;
                }
            } else { consecutiveUnknownTools = 0; }
            for (int i = 0; i < toolCalls.size(); i++) {
                eventSink.accept(new AgentEvent.ToolCallStart(i, toolCalls.get(i).id(), toolCalls.get(i).name()));
            }
            PermissionMode mode = planMode ? PermissionMode.PLAN : permMode;
            PermissionContext permCtx = new PermissionContext(mode, PermissionConfig.load(Path.of("").toAbsolutePath()), Path.of("").toAbsolutePath());
            List<ToolResult> results = ToolExecutor.executeAll(toolCalls, tools, eventSink, pipeline, permCtx);
            for (int i = 0; i < results.size(); i++) {
                ToolResult r = results.get(i);
                if (!r.askPending()) continue;
                ToolCall tc = toolCalls.get(i);
                Tool tool = tools.get(tc.name());
                CompletableFuture<String> future = new CompletableFuture<>();
                eventSink.accept(new AgentEvent.PermissionAsk(tc.id(), tc.name(),
                        extractPreview(tc), "当前权限模式要求确认此操作", future));
                try {
                    String choice = future.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if ("allow".equals(choice) || "permanent".equals(choice)) {
                        if ("permanent".equals(choice)) writePermanentRule(tc);
                        results.set(i, tool.execute(tc.input()));
                        eventSink.accept(new AgentEvent.ToolCallEnd(i, tc.id(), tc.name(), results.get(i)));
                    } else { results.set(i, ToolResult.err(tc.name(), "用户拒绝", 0)); }
                } catch (Exception e) {
                    results.set(i, ToolResult.err(tc.name(), "已取消", 0));
                }
            }
            List<MessageBlock> useBlocks = new ArrayList<>();
            List<MessageBlock> resultBlocks = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolCall tc = toolCalls.get(i);
                ToolResult r = results.get(i);
                useBlocks.add(new MessageBlock.ToolUseBlock(tc.id(), tc.name(), tc.input()));
                resultBlocks.add(new MessageBlock.ToolResultBlock(tc.id(), r.content(), !r.success()));
            }
            conversation.addMessage(new MessageRecord(Role.ASSISTANT, "", useBlocks));
            // F19a: 文件追踪——ReadFile 成功后记录
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolCall tc = toolCalls.get(i);
                ToolResult r = results.get(i);
                if ("read_file".equals(tc.name()) && r.success() && tc.input().has("path")) {
                    contextManager.recordFileRead(tc.input().get("path").asText(), r.content());
                }
            }
            conversation.addMessage(new MessageRecord(Role.USER, "", resultBlocks));
        }
        eventSink.accept(new AgentEvent.AgentFinished(null, MAX_ITERATIONS, totalInputTokens, totalOutputTokens));
        return null;
    }

    public void cancel() { cancelled = true; }

    public void setPlanMode(boolean v) { planMode = v; }
    public void setPermMode(PermissionMode m) { permMode = m; }
    public PermissionMode getPermMode() { return permMode; }

    /** /compact 命令入口（F22） */
    public CompressEvent forceCompact(List<JsonNode> tools) {
        return contextManager.manualCompact(this.conversation, tools);
    }

    private String extractPreview(ToolCall tc) {
        if (tc.input().has("command")) return tc.input().get("command").asText();
        if (tc.input().has("path")) return tc.input().get("path").asText();
        return tc.input().toString();
    }

    private void writePermanentRule(ToolCall tc) {
        try {
            String friendlyName = switch (tc.name()) {
                case "read_file" -> "Read"; case "write_file" -> "Write";
                case "edit_file" -> "Edit"; case "exec_command" -> "Bash";
                case "find_files" -> "Glob"; case "grep_code" -> "Grep";
                default -> tc.name();
            };
            String param = "";
            if (tc.input().has("command")) param = tc.input().get("command").asText();
            else if (tc.input().has("path")) param = tc.input().get("path").asText();
            String rule = param.isEmpty() ? friendlyName : friendlyName + "(" + param + ")";
            Path configPath = Path.of("easycode.permissions.yaml");
            String content = java.nio.file.Files.exists(configPath)
                ? java.nio.file.Files.readString(configPath) + "\n" : "rules:\n";
            java.nio.file.Files.writeString(configPath, content + "  - " + rule + "\n");
        } catch (Exception ignored) {}
    }
}
