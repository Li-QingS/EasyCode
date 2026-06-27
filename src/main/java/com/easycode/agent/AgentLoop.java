package com.easycode.agent;

import com.easycode.config.Config;
import com.easycode.context.ContextManager;
import com.easycode.hook.HookEngine;
import com.easycode.hook.HookEvent;
import com.easycode.team.lead.CoordinatorMode;
import com.easycode.memory.MemoryStore;
import com.easycode.memory.MemoryUpdater;
import com.easycode.context.CompressEvent;
import com.easycode.context.SessionManager;
import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.subagent.TaskManager;
import com.easycode.subagent.TaskRecord;
import com.easycode.permission.PermissionConfig;
import com.easycode.permission.PermissionContext;
import com.easycode.permission.PermissionMode;
import com.easycode.permission.PermissionPipeline;
import com.easycode.prompt.Environment;
import com.easycode.prompt.Prompt;
import com.easycode.skill.SkillRegistry;
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
import java.util.Set;
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
    private final String instructions;
    private final String memoryIndex;
    private final MemoryStore projectMemory;
    private final MemoryStore userMemory;
    private final SkillRegistry skillRegistry;
    private final TaskManager taskManager;
    private final com.easycode.subagent.WorktreeManager worktreeManager;
    private final HookEngine hookEngine;
    private int turnCount;
    private int totalInputTokens;
    private int totalOutputTokens;
    private int emptyTextRetries;
    private static final String EMPTY_TEXT_NUDGE = "[系统提示] 你上一轮没有输出任何文本。请根据之前的工具调用结果，给出你的分析和回答。不要只调工具不说话，也不要让输出为空。";

    public AgentLoop(LlmProvider provider, ToolRegistry tools,
                     ConversationMgr conversation, Config config, String appVersion,
                     String instructions, String memoryIndex) {
        this(provider, tools, conversation, config, appVersion, instructions, memoryIndex, null, null, null, null);
    }

    public AgentLoop(LlmProvider provider, ToolRegistry tools,
                     ConversationMgr conversation, Config config, String appVersion,
                     String instructions, String memoryIndex, SkillRegistry skillRegistry) {
        this(provider, tools, conversation, config, appVersion, instructions, memoryIndex, skillRegistry, null, null, null);
    }

    public AgentLoop(LlmProvider provider, ToolRegistry tools,
                     ConversationMgr conversation, Config config, String appVersion,
                     String instructions, String memoryIndex, SkillRegistry skillRegistry,
                     HookEngine hookEngine, TaskManager taskManager,
                     com.easycode.subagent.WorktreeManager worktreeManager) {
        this.provider = provider;
        this.tools = tools;
        this.conversation = conversation;
        this.config = config;
        this.appVersion = appVersion;
        this.instructions = instructions;
        this.memoryIndex = memoryIndex;
        this.skillRegistry = skillRegistry;
        this.hookEngine = hookEngine != null ? hookEngine : new HookEngine(java.util.List.of());
        this.taskManager = taskManager;
        this.worktreeManager = worktreeManager;
        this.contextManager = new ContextManager(provider, config, SessionManager.sessionId());
        this.projectMemory = new MemoryStore(Path.of(".easycode/memory"));
        this.userMemory = new MemoryStore(Path.of(java.lang.System.getProperty("user.home"), ".easycode/memory"));
    }

    public String run(String userMessage, Consumer<AgentEvent> eventSink) {
        cancelled = false;
        // 拉取已完成的后台子 Agent 结果，注入对话
        if (taskManager != null) {
            for (TaskRecord r : taskManager.drainCompleted()) {
                String summary = "[后台子 Agent 完成]\\n任务: " + r.agentName()
                    + "\\n状态: " + r.status() + "\\n轮次: " + r.turnsUsed()
                    + "\\n\\n--- 输出 ---\\n" + r.output();
                conversation.addUserMessage(summary);
            }
        }
        int consecutiveUnknownTools = 0;
        emptyTextRetries = 0;
        conversation.addUserMessage(userMessage);
        Environment env = Environment.collect(appVersion, config.model());
        String activatedSkills = skillRegistry != null ? skillRegistry.getActivatedPrompt() : null;
        String stablePrompt = Prompt.buildWithSkills(instructions, memoryIndex, activatedSkills);
        // Hook: session-start prompt injection
        String sessionPrompts = hookEngine.collectPrompts(HookEvent.SESSION_START, java.util.Map.of("sessionId", SessionManager.sessionId()));
        if (!sessionPrompts.isEmpty()) stablePrompt = stablePrompt + "\n\n" + sessionPrompts;
            hookEngine.fire(HookEvent.SESSION_START, java.util.Map.of("sessionId", SessionManager.sessionId()));
        PermissionPipeline pipeline = new PermissionPipeline(PermissionConfig.load(Path.of("").toAbsolutePath()));
        if (permMode == PermissionMode.DEFAULT) permMode = pipeline.startMode();
        for (int round = 1; round <= MAX_ITERATIONS; round++) {
            if (cancelled) {
                eventSink.accept(new AgentEvent.Error("已取消", false));
                eventSink.accept(new AgentEvent.AgentFinished(null, round - 1, totalInputTokens, totalOutputTokens));
                return null;
            }
            eventSink.accept(new AgentEvent.IterationProgress(round, MAX_ITERATIONS));
            // 每轮开始前检查已完成后台子 Agent 任务
            if (taskManager != null) {
                for (TaskRecord r : taskManager.drainCompleted()) {
                    String summary = "[后台子 Agent 完成]\n任务: " + r.agentName()
                        + "\n状态: " + r.status() + "\n轮次: " + r.turnsUsed()
                        + "\n\n--- 输出 ---\n" + r.output();
                    conversation.addUserMessage(summary);
                }
            }
            // Hook: turn-start prompt injection
            String turnPrompts = hookEngine.collectPrompts(HookEvent.TURN_START, java.util.Map.of("round", round));
            hookEngine.fire(HookEvent.TURN_START, java.util.Map.of("round", round));
            Set<String> whitelist = (skillRegistry != null) ? skillRegistry.activeToolWhitelist() : java.util.Collections.emptySet();
            List<JsonNode> toolsJson;
            if (planMode) {
                if (!whitelist.isEmpty()) {
                    toolsJson = tools.toToolsJson(whitelist);
                } else {
                    toolsJson = tools.toToolsJson(); // Plan 模式展示全部工具
                }
            } else {
                if (!whitelist.isEmpty()) {
                    toolsJson = tools.toToolsJson(whitelist);
                } else {
                    toolsJson = tools.toToolsJson();
                }
            }
            // Coordinator 模式：移除写文件工具
            if (CoordinatorMode.isActive()) {
                toolsJson = toolsJson.stream()
                    .filter(n -> {
                        String name = n.has("name") ? n.get("name").asText() : "";
                        return !"write_file".equals(name) && !"edit_file".equals(name);
                    })
                    .collect(java.util.stream.Collectors.toList());
            }
            String reminder = "";
            if (planMode) {
                reminder = Reminder.planReminder(Reminder.isFullReminder(round));
            }
            // ch08: 上下文管理
            // 每轮请求前修复 role 交替违规（防止 API 截断返回空文本）
            conversation.fixRoleAlternation();
            CompressEvent autoEvt = contextManager.autoManage(this.conversation, toolsJson);
            if (autoEvt.replacedCount() > 0 || !autoEvt.success()) {
                eventSink.accept(new AgentEvent.ContextCompress(autoEvt));
            }
            contextManager.markRequested(conversation.getHistory().size());
            // Hook: pre-llm-request
            var preLlmVars = new java.util.HashMap<String, Object>();
            preLlmVars.put("messageCount", conversation.getHistory().size());
            hookEngine.fire(HookEvent.PRE_LLM_REQUEST, preLlmVars);
            String preLlmPrompts = hookEngine.collectPrompts(HookEvent.PRE_LLM_REQUEST, preLlmVars);
            String effectiveSystem = stablePrompt + (preLlmPrompts.isEmpty() ? "" : "\n\n" + preLlmPrompts);
            // Also apply turn-start prompts if collected
            if (!turnPrompts.isEmpty()) effectiveSystem = effectiveSystem + "\n\n" + turnPrompts;
            Request req = new Request(conversation.getHistory(), toolsJson,
                    new System(effectiveSystem, env.render()), reminder);
            StreamingCollector collector = new StreamingCollector(eventSink);
            try {
                provider.chatStream(req, collector);
            } catch (Exception e) {
                // F25-F26: prompt_too_long 触发紧急压缩后重试
                if (isPromptTooLong(e) && round <= 3) {
                    eventSink.accept(new AgentEvent.Error("上下文超限，触发紧急压缩...", true));
                    CompressEvent emEvt = contextManager.emergencyCompact(this.conversation, toolsJson);
                    eventSink.accept(new AgentEvent.ContextCompress(emEvt));
                    if (emEvt.success()) {
                        contextManager.markRequested(conversation.getHistory().size());
                        try {
                            provider.chatStream(new Request(conversation.getHistory(), toolsJson,
                                new System(stablePrompt, env.render()), reminder), collector);
                            // 重试成功，继续正常流程
                        } catch (Exception retryEx) {
                            eventSink.accept(new AgentEvent.Error("紧急压缩后仍失败: " + retryEx.getMessage(), false));
                            eventSink.accept(new AgentEvent.AgentFinished(null, round, totalInputTokens, totalOutputTokens));
                            return null;
                        }
                        if (collector.hasError()) {
                            eventSink.accept(new AgentEvent.Error(collector.getErrorMessage(), false));
                            eventSink.accept(new AgentEvent.AgentFinished(null, round, totalInputTokens, totalOutputTokens));
                            return null;
                        }
                        // 重试成功，跳过下面的错误处理，继续正常流程
                    } else {
                        eventSink.accept(new AgentEvent.Error("紧急压缩失败: " + emEvt.errorMessage(), false));
                        eventSink.accept(new AgentEvent.AgentFinished(null, round, totalInputTokens, totalOutputTokens));
                        return null;
                    }
                } else {
                    eventSink.accept(new AgentEvent.Error("Provider error: " + e.getMessage(), false));
                    eventSink.accept(new AgentEvent.AgentFinished(null, round, totalInputTokens, totalOutputTokens));
                    return null;
                }
            }
            if (collector.hasError()) {
                eventSink.accept(new AgentEvent.Error(collector.getErrorMessage(), false));
                eventSink.accept(new AgentEvent.AgentFinished(null, round, totalInputTokens, totalOutputTokens));
                return null;
            }
            int roundIn = collector.getRoundInputTokens();
            int roundOut = collector.getRoundOutputTokens();
            // Hook: post-llm-response
            var postLlmVars = new java.util.HashMap<String, Object>();
            postLlmVars.put("text", collector.getFullText());
            postLlmVars.put("toolCallCount", collector.getToolCalls().size());
            hookEngine.fire(HookEvent.POST_LLM_RESPONSE, postLlmVars);
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
                // AgentLoop 完成时无条件清除 Skill 白名单
                if (skillRegistry != null) skillRegistry.clearWhitelist();
                hookEngine.fire(HookEvent.SESSION_END, java.util.Map.of("totalRounds", round));
                eventSink.accept(new AgentEvent.AgentFinished(finalText, round, totalInputTokens, totalOutputTokens));
                return finalText;
            }
            String thinkingText = collector.getThinkingText();
            if (toolCalls.isEmpty() && !thinkingText.isBlank()) {
                emptyTextRetries = 0;
                conversation.addAssistantMessage(thinkingText);
                if (skillRegistry != null) skillRegistry.clearWhitelist();
                hookEngine.fire(HookEvent.SESSION_END, java.util.Map.of("totalRounds", round));
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
                // 注入 nudge 消息——合并到最后一条 USER 消息，避免制造 USER→USER 违规
                var hist = conversation.getHistory();
                var last = hist.get(hist.size() - 1);
                if (last.role() == Role.USER) {
                    String merged = last.content() + "\n\n" + EMPTY_TEXT_NUDGE;
                    conversation.replaceMessage(hist.size() - 1,
                        new MessageRecord(Role.USER, merged, last.blocks()));
                } else {
                    conversation.addUserMessage(EMPTY_TEXT_NUDGE);
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
            // Plan/Whitelist 保护：拒绝不在本轮 toolsJson 中的工具调用（LLM 幻觉调用）
            java.util.Set<String> allowedThisRound = toolsJson.stream()
                .map(n -> n.get("name").asText())
                .collect(java.util.stream.Collectors.toSet());
            java.util.List<ToolCall> screenedCalls = new java.util.ArrayList<>(toolCalls);
            toolCalls = new java.util.ArrayList<>();
            java.util.List<MessageBlock> rejectedUse = new java.util.ArrayList<>();
            java.util.List<MessageBlock> rejectedResults = new java.util.ArrayList<>();
            for (ToolCall tc : screenedCalls) {
                    if (allowedThisRound.contains(tc.name())) {
                        if (planMode) {
                            try {
                                if (tools.getPermission(tc.name()) == Tool.Permission.READ_WRITE) {
                                    rejectedUse.add(new MessageBlock.ToolUseBlock(tc.id(), tc.name(), tc.input()));
                                    rejectedResults.add(new MessageBlock.ToolResultBlock(tc.id(),
                                        "Plan 模式拒绝写操作: " + tc.name() + "（在计划中描述此操作，切换模式后执行）", true));
                                } else {
                                    toolCalls.add(tc);
                                }
                            } catch (IllegalArgumentException e) { toolCalls.add(tc); }
                        } else {
                            toolCalls.add(tc);
                        }
                } else {
                    rejectedUse.add(new MessageBlock.ToolUseBlock(tc.id(), tc.name(), tc.input()));
                    rejectedResults.add(new MessageBlock.ToolResultBlock(tc.id(),
                        "系统拒绝: 当前模式下不允许调用 " + tc.name() + "（仅允许只读工具）", true));
                }
            }
            if (!rejectedResults.isEmpty()) {
                conversation.addMessage(new MessageRecord(Role.ASSISTANT, "", rejectedUse));
                conversation.addMessage(new MessageRecord(Role.USER, "", rejectedResults));
                if (toolCalls.isEmpty()) continue;
            }
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
            // ch09: 记忆更新——每 5 轮或含关键词时异步触发（F35-F36）
            turnCount++;
            String latestUser = userMessage;
            boolean hasKeyword = latestUser != null && (latestUser.contains("记住") || latestUser.contains("记忆")
                || latestUser.contains("别忘") || latestUser.contains("remember") || latestUser.contains("memo"));
            if (turnCount % 5 == 0 || hasKeyword) {
                List<MessageRecord> recent = conversation.getHistory();
                MemoryUpdater.updateAsync(provider, recent, projectMemory, userMemory);
            }
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolCall tc = toolCalls.get(i);
                ToolResult r = results.get(i);
                if ("read_file".equals(tc.name()) && r.success() && tc.input().has("path")) {
                    contextManager.recordFileRead(tc.input().get("path").asText(), r.content());
                }
            }
            conversation.addMessage(new MessageRecord(Role.USER, "", resultBlocks));
        }
        if (skillRegistry != null) skillRegistry.clearWhitelist();
        hookEngine.fire(HookEvent.SESSION_END, java.util.Map.of("totalRounds", MAX_ITERATIONS));
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

    /** 加载会话历史（F21：恢复会话时使用） */
    public void loadHistory(java.util.List<MessageRecord> messages) {
        conversation.replaceAll(new java.util.ArrayList<>(messages));
        contextManager.reset();
    }

    /** 获取会话管理器的引用（供 Tui 使用） */
    public ConversationMgr conversation() { return conversation; }

    /** 获取 LLM Provider（供 SubAgent 复用） */
    public LlmProvider getProvider() { return provider; }

    /** 获取 Config（供 SubAgent 复用） */
    public Config getConfig() { return config; }

    /** 获取 HookEngine（供 SubAgent 复用） */
    public HookEngine getHookEngine() { return hookEngine; }

    private static boolean isPromptTooLong(Throwable e) {
        if (e == null) return false;
        String msg = (e.getMessage() != null ? e.getMessage() : "").toLowerCase();
        if (msg.contains("prompt_too_long") || msg.contains("prompt too long")
            || msg.contains("提示词过长") || msg.contains("token") && msg.contains("exceed"))
            return true;
        return isPromptTooLong(e.getCause());
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
