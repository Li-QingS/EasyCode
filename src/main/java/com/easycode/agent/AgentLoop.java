package com.easycode.agent;

import com.easycode.config.Config;
import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.prompt.Environment;
import com.easycode.prompt.Prompt;
import com.easycode.prompt.Reminder;
import com.easycode.provider.LlmProvider;
import com.easycode.provider.Request;
import com.easycode.provider.StreamHandler;
import com.easycode.provider.System;
import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
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
    }

    public String run(String userMessage, Consumer<AgentEvent> eventSink) {
        cancelled = false;
        int consecutiveUnknownTools = 0;
        emptyTextRetries = 0;

        conversation.addUserMessage(userMessage);

        Environment env = Environment.collect(appVersion, config.model());
        String stablePrompt = Prompt.buildStable();

        for (int round = 1; round <= MAX_ITERATIONS; round++) {
            if (cancelled) {
                eventSink.accept(new AgentEvent.Error("已取消", false));
                eventSink.accept(new AgentEvent.AgentFinished(null, round - 1,
                        totalInputTokens, totalOutputTokens));
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

            Request req = new Request(conversation.getHistory(), toolsJson,
                    new System(stablePrompt, env.render()), reminder);

            StreamingCollector collector = new StreamingCollector(eventSink);
            try {
                provider.chatStream(req, collector);
            } catch (Exception e) {
                eventSink.accept(new AgentEvent.Error("Provider 流出错: " + e.getMessage(), false));
                eventSink.accept(new AgentEvent.AgentFinished(null, round,
                        totalInputTokens, totalOutputTokens));
                return null;
            }

            if (collector.hasError()) {
                eventSink.accept(new AgentEvent.Error(collector.getErrorMessage(), false));
                eventSink.accept(new AgentEvent.AgentFinished(null, round,
                        totalInputTokens, totalOutputTokens));
                return null;
            }

            int roundIn = collector.getRoundInputTokens();
            int roundOut = collector.getRoundOutputTokens();
            totalInputTokens += roundIn;
            totalOutputTokens += roundOut;
            eventSink.accept(new AgentEvent.TokenUsage(roundIn, roundOut,
                    totalInputTokens, totalOutputTokens, 0, 0));

            List<ToolCall> toolCalls = collector.getToolCalls();
            String finalText = collector.getFullText();

            if (toolCalls.isEmpty() && !finalText.isBlank()) {
                emptyTextRetries = 0;
                conversation.addAssistantMessage(finalText);
                eventSink.accept(new AgentEvent.AgentFinished(finalText, round,
                        totalInputTokens, totalOutputTokens));
                return finalText;
            }

            String thinkingText = collector.getThinkingText();
            if (toolCalls.isEmpty() && !thinkingText.isBlank()) {
                emptyTextRetries = 0;
                conversation.addAssistantMessage(thinkingText);
                eventSink.accept(new AgentEvent.AgentFinished(thinkingText, round,
                        totalInputTokens, totalOutputTokens));
                return thinkingText;
            }

            if (toolCalls.isEmpty()) {
                emptyTextRetries++;
                if (emptyTextRetries > MAX_EMPTY_TEXT_RETRIES) {
                    eventSink.accept(new AgentEvent.Error(
                            "模型连续 " + MAX_EMPTY_TEXT_RETRIES + " 次返回空文本，已停止", false));
                    eventSink.accept(new AgentEvent.AgentFinished(null, round,
                            totalInputTokens, totalOutputTokens));
                    return null;
                }
                conversation.addUserMessage("请给出文字回复，总结你的发现。");
                continue;
            }

            emptyTextRetries = 0;

            boolean allUnknown = true;
            for (ToolCall tc : toolCalls) {
                try {
                    tools.get(tc.name());
                    allUnknown = false;
                } catch (IllegalArgumentException e) {}
            }

            if (allUnknown) {
                consecutiveUnknownTools++;
                if (consecutiveUnknownTools >= MAX_CONSECUTIVE_UNKNOWN) {
                    eventSink.accept(new AgentEvent.Error(
                            "连续 " + MAX_CONSECUTIVE_UNKNOWN + " 轮请求未知工具，已停止", false));
                    List<MessageBlock> unknownUseBlocks = new ArrayList<>();
                    List<MessageBlock> unknownResultBlocks = new ArrayList<>();
                    for (ToolCall tc : toolCalls) {
                        unknownUseBlocks.add(new MessageBlock.ToolUseBlock(tc.id(), tc.name(), tc.input()));
                        unknownResultBlocks.add(new MessageBlock.ToolResultBlock(tc.id(), "未知工具: " + tc.name(), true));
                    }
                    conversation.addMessage(new MessageRecord(Role.ASSISTANT, "", unknownUseBlocks));
                    conversation.addMessage(new MessageRecord(Role.USER, "", unknownResultBlocks));
                    eventSink.accept(new AgentEvent.AgentFinished(null, round,
                            totalInputTokens, totalOutputTokens));
                    return null;
                }
            } else {
                consecutiveUnknownTools = 0;
            }

            for (int i = 0; i < toolCalls.size(); i++) {
                ToolCall tc = toolCalls.get(i);
                eventSink.accept(new AgentEvent.ToolCallStart(i, tc.id(), tc.name()));
            }

            List<ToolResult> results = ToolExecutor.executeAll(toolCalls, tools, eventSink);

            List<MessageBlock> toolUseBlocks = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                toolUseBlocks.add(new MessageBlock.ToolUseBlock(tc.id(), tc.name(), tc.input()));
            }
            conversation.addMessage(new MessageRecord(Role.ASSISTANT, "", toolUseBlocks));

            List<MessageBlock> toolResultBlocks = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolCall tc = toolCalls.get(i);
                ToolResult result = results.get(i);
                toolResultBlocks.add(new MessageBlock.ToolResultBlock(tc.id(), result.content(), !result.success()));
            }
            conversation.addMessage(new MessageRecord(Role.USER, "", toolResultBlocks));

            eventSink.accept(new AgentEvent.RoundComplete(round));
        }

        eventSink.accept(new AgentEvent.Error("达到迭代上限 (" + MAX_ITERATIONS + " 轮)", false));
        eventSink.accept(new AgentEvent.AgentFinished(null, MAX_ITERATIONS,
                totalInputTokens, totalOutputTokens));
        return null;
    }

    public void cancel() { cancelled = true; }
    public void setPlanMode(boolean planMode) { this.planMode = planMode; }
    public boolean isPlanMode() { return planMode; }
}
