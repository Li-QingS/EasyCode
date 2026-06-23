package com.easycode.subagent;

import com.easycode.agent.AgentLoop;
import com.easycode.agent.AgentEvent;
import com.easycode.config.Config;
import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageRecord;
import com.easycode.hook.HookEngine;
import com.easycode.provider.LlmProvider;
import com.easycode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 子 Agent 运行器：独立上下文 + 跑到底执行 */
public class SubAgent implements Callable<TaskRecord> {

    private final String taskId;
    private final AgentDef def;
    private final String prompt;
    private final List<MessageRecord> seedMessages;
    private final LlmProvider provider;
    private final Config config;
    private final HookEngine hookEngine;
    private final ToolRegistry filteredTools;
    private final Path worktreeRoot;

    private final AtomicReference<TaskRecord> resultRef = new AtomicReference<>();

    public SubAgent(String taskId, AgentDef def, String prompt,
                    List<MessageRecord> seedMessages,
                    LlmProvider provider, Config config, HookEngine hookEngine,
                    ToolRegistry filteredTools) {
        this(taskId, def, prompt, seedMessages, provider, config, hookEngine,
            filteredTools, null);
    }

    public SubAgent(String taskId, AgentDef def, String prompt,
                    List<MessageRecord> seedMessages,
                    LlmProvider provider, Config config, HookEngine hookEngine,
                    ToolRegistry filteredTools, Path worktreeRoot) {
        this.taskId = taskId;
        this.def = def;
        this.prompt = prompt;
        this.seedMessages = seedMessages != null ? seedMessages : List.of();
        this.provider = provider;
        this.config = config;
        this.hookEngine = hookEngine;
        this.worktreeRoot = worktreeRoot;
        // Worktree 隔离：包装工具注册表，重定向所有文件操作路径
        this.filteredTools = worktreeRoot != null
            ? WorktreedToolRegistry.wrap(filteredTools, worktreeRoot)
            : filteredTools;
    }

    /** Worktree 根路径，null 表示不隔离 */
    public Path worktreeRoot() { return worktreeRoot; }

    @Override
    public TaskRecord call() {
        long start = System.currentTimeMillis();
        String fullPrompt = def.systemPrompt();
        if (prompt != null && !prompt.isBlank()) {
            fullPrompt = fullPrompt + "\n\n## 当前任务\n\n" + prompt;
        }

        // 独立 ConversationMgr
        ConversationMgr conv = new ConversationMgr(
            msg -> {},  // no-op writer for sub-agents
            msgs -> {}  // no-op compact writer
        );

        // 注入 seed messages（Fork 模式）
        for (MessageRecord seed : seedMessages) {
            conv.addMessage(seed);
        }

        // 构造内部 AgentLoop（不传 SkillRegistry，子 Agent 不需要 skill 系统）
        String instructions = "你是 " + def.name() + "。" + fullPrompt;
        AgentLoop loop = new AgentLoop(provider, filteredTools, conv, config, "1.0.0",
            instructions, "");

        AtomicInteger turns = new AtomicInteger(0);
        AtomicInteger inTok = new AtomicInteger(0);
        AtomicInteger outTok = new AtomicInteger(0);
        AtomicReference<String> finalText = new AtomicReference<>("");

        // 跑到底：非交互执行
        try {
            loop.run(prompt, event -> {
                if (event instanceof AgentEvent.TokenUsage tu) {
                    inTok.addAndGet(tu.roundInput());
                    outTok.addAndGet(tu.roundOutput());
                }
                if (event instanceof AgentEvent.AgentFinished af) {
                    if (af.finalText() != null) finalText.set(af.finalText());
                    turns.set(af.totalRounds());
                }
            });
        } catch (Exception e) {
            return new TaskRecord(taskId, def.name(), TaskStatus.ERROR,
                "子 Agent 异常: " + e.getMessage(), turns.get(),
                inTok.get(), outTok.get(), start, System.currentTimeMillis());
        }

        // 检查是否达到最大轮次
        if (turns.get() >= def.maxTurns() && finalText.get().isBlank()) {
            return new TaskRecord(taskId, def.name(), TaskStatus.ERROR,
                "达到最大轮次 " + def.maxTurns() + " 但无有效输出",
                turns.get(), inTok.get(), outTok.get(), start, System.currentTimeMillis());
        }

        String output = finalText.get().isBlank() ? "(子 Agent 完成但无文本输出)" : finalText.get();
        return new TaskRecord(taskId, def.name(), TaskStatus.DONE,
            output, turns.get(), inTok.get(), outTok.get(), start, System.currentTimeMillis());
    }
}
