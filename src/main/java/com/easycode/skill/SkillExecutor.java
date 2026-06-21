package com.easycode.skill;

import com.easycode.config.Config;
import com.easycode.context.ContextManager;
import com.easycode.context.CompressEvent;
import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.provider.LlmProvider;
import com.easycode.provider.ProviderFactory;
import com.easycode.provider.Request;
import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 执行模式：inline 注入当前对话，fork 隔离执行并回流 */
public final class SkillExecutor {
    private static final String SKILL_END = "<!-- SKILL_END -->";
    private static final int FORK_MAX_ROUNDS = 15;
    private static final int MAX_EMPTY_TEXT_RETRIES = 3;
    private static final String EMPTY_TEXT_NUDGE =
        "[系统提示] 你上一轮没有输出任何文本。请根据之前的工具调用结果，给出你的分析和回答。不要只调工具不说话，也不要让输出为空。";

    private SkillExecutor() {}

    public static String execute(SkillDef skill, String arguments,
                                  ConversationMgr conv, ToolRegistry tools,
                                  LlmProvider provider, Config config) {
        String mode = skill.frontmatter().mode();
        if ("fork".equals(mode)) {
            return fork(skill, arguments, conv, tools, provider, config);
        }
        return inline(skill, arguments, conv);
    }

    // ====== inline 模式 ======

    private static String inline(SkillDef skill, String arguments, ConversationMgr conv) {
        String prompt = buildPrompt(skill, arguments);
        conv.addUserMessage(prompt);
        return prompt;
    }

    // ====== fork 模式（异步轻量循环） ======

    private static String fork(SkillDef skill, String arguments,
                                 ConversationMgr conv, ToolRegistry tools,
                                 LlmProvider provider, Config config) {
        // 准备上下文和工具
        final String prompt = buildPrompt(skill, arguments);
        final List<MessageRecord> history = buildForkHistory(skill, conv, provider, prompt, config);

        // 过滤工具
        final ToolRegistry forkTools = new ToolRegistry();
        List<String> allowed = skill.frontmatter().allowedTools();
        for (JsonNode toolJson : tools.toToolsJson()) {
            String name = toolJson.get("name").asText();
            if (allowed.isEmpty() || allowed.contains(name) || "load_skill".equals(name)) {
                forkTools.register(tools.get(name));
            }
        }

        // Provider（可选 model 切换）
        final LlmProvider forkProvider = resolveProvider(skill, provider, config);

        // 异步跑轻量循环
        new Thread(() -> {
            String result = runForkLoop(skill.frontmatter().name(), forkProvider, forkTools,
                history, config);
            // 结果回流
            boolean isError = result != null && result.startsWith("(Skill");
            if (result != null && !result.isBlank() && !isError) {
                conv.addMessage(new MessageRecord(Role.ASSISTANT, result, Collections.emptyList()));
                System.out.println("\n[Skill " + skill.frontmatter().name() + " 完成]\n" + result);
            } else if (isError) {
                System.out.println("\n[Skill " + skill.frontmatter().name() + " 失败] " + result);
            } else {
                System.out.println("\n[Skill " + skill.frontmatter().name() + " 未产生输出]");
            }
        }, "skill-fork-" + skill.frontmatter().name()).start();

        return "(fork 模式 Skill 在后台执行中...)";
    }

    // ====== fork 轻量循环 ======

    private static String runForkLoop(String skillName, LlmProvider provider,
                                       ToolRegistry tools, List<MessageRecord> history,
                                       Config config) {
        // 用 ConversationMgr + ContextManager，跟主 AgentLoop 对齐
        ConversationMgr forkConv = new ConversationMgr(m -> {}, ms -> {});
        for (MessageRecord msg : history) {
            forkConv.addMessage(msg);
        }
        ContextManager ctxMgr = new ContextManager(provider, config, "fork-" + skillName);

        int emptyTextRetries = 0;

        for (int round = 1; round <= FORK_MAX_ROUNDS; round++) {
            // 每轮：修复 role 交替违规，再压缩上下文（对齐主 AgentLoop）
            forkConv.fixRoleAlternation();
            List<JsonNode> toolsJson = tools.toToolsJson();
            CompressEvent evt = ctxMgr.autoManage(forkConv, toolsJson);
            ctxMgr.markRequested(forkConv.getHistory().size());

            // 构建请求
            String stablePrompt = com.easycode.prompt.Prompt.buildSystemPrompt("", "");
            com.easycode.prompt.Environment env =
                com.easycode.prompt.Environment.collect("1.0.0", config.model());
            Request req = new Request(forkConv.getHistory(), toolsJson,
                new com.easycode.provider.System(stablePrompt, env.render()), "");

            // 调用 LLM
            com.easycode.agent.StreamingCollector collector =
                new com.easycode.agent.StreamingCollector(e -> {});
            try {
                provider.chatStream(req, collector);
            } catch (Exception e) {
                System.err.println("[Fork " + skillName + "] 第" + round + "轮 LLM 调用失败: " + e.getMessage());
                return null;
            }
            if (collector.hasError()) {
                System.err.println("[Fork " + skillName + "] 第" + round + "轮出错: " + collector.getErrorMessage());
                return null;
            }

            // 追踪 token 用量
            ctxMgr.updateUsage(
                collector.getRoundInputTokens(), collector.getCacheReadTokens(), 0,
                collector.getRoundOutputTokens());

            String text = collector.getFullText();
            List<ToolCall> toolCalls = collector.getToolCalls();

            // 构建 assistant 消息（含文本和 tool_use blocks）
            List<MessageBlock> blocks = new ArrayList<>();
            if (text != null && !text.isBlank()) {
                blocks.add(new MessageBlock.TextBlock(text));
            }
            for (ToolCall tc : toolCalls) {
                blocks.add(new MessageBlock.ToolUseBlock(
                    tc.id(), tc.name(), tc.input()));
            }
            forkConv.addMessage(new MessageRecord(Role.ASSISTANT, "", blocks));

            // 有工具调用 → 执行并继续
            if (!toolCalls.isEmpty()) {
                emptyTextRetries = 0;
                List<MessageBlock> resultBlocks = new ArrayList<>();
                for (ToolCall tc : toolCalls) {
                    ToolResult tr;
                    try {
                        Tool tool = tools.get(tc.name());
                        tr = tool.execute(tc.input());
                    } catch (Exception e) {
                        tr = ToolResult.err(tc.name(), e.getMessage(), 0);
                    }
                    resultBlocks.add(new MessageBlock.ToolResultBlock(
                        tc.id(), tr.content(), !tr.success()));
                }
                forkConv.addMessage(new MessageRecord(Role.USER, "", resultBlocks));
                continue;
            }

            // 无工具调用 + 有文字 = 正常结束
            if (text != null && !text.isBlank()) {
                return text;
            }

            // 空输出：重试（对齐主 AgentLoop 的 EMPTY_TEXT_NUDGE 机制）
            emptyTextRetries++;
            if (emptyTextRetries >= MAX_EMPTY_TEXT_RETRIES) {
                System.err.println("[Fork " + skillName + "] 连续 " + MAX_EMPTY_TEXT_RETRIES
                    + " 轮无输出，已停止");
                return null;
            }
            var last = forkConv.getHistory().get(forkConv.getHistory().size() - 1);
            if (last.role() == Role.USER) {
                String merged = last.content() + "\n\n" + EMPTY_TEXT_NUDGE;
                forkConv.replaceMessage(forkConv.getHistory().size() - 1,
                    new MessageRecord(Role.USER, merged, last.blocks()));
            } else {
                forkConv.addUserMessage(EMPTY_TEXT_NUDGE);
            }
        }
        return "(Skill 达到最大轮次，未完成)";
    }

    // ====== 辅助 ======

    static String buildPrompt(SkillDef skill, String arguments) {
        String body = skill.promptBody();
        String args = arguments != null ? arguments : "";
        body = body.replace("$ARGUMENTS", args);
        if (!skill.frontmatter().allowedTools().isEmpty() && !body.contains(SKILL_END)) {
            body += "\n\n任务完成后请在回复末尾输出 `" + SKILL_END + "`。";
        }
        return body;
    }

    private static List<MessageRecord> buildForkHistory(SkillDef skill, ConversationMgr conv,
                                                         LlmProvider provider, String prompt,
                                                         Config config) {
        List<MessageRecord> history = new ArrayList<>();
        String ctx = skill.frontmatter().context();
        if ("none".equals(ctx)) {
            // 空历史 — 仅 Skill prompt
        } else if ("full".equals(ctx)) {
            // 全量上下文：先用 ContextManager 压缩再拷贝
            ConversationMgr tmpConv = new ConversationMgr(m -> {}, ms -> {});
            for (MessageRecord mr : conv.getHistory()) tmpConv.addMessage(mr);
            ContextManager tmpCtx = new ContextManager(provider, config, "fork-ctx-" + skill.frontmatter().name());
            try {
                // manualCompact 会做 LLM 摘要压缩，比裸 SummaryGenerator 更完整
                tmpCtx.manualCompact(tmpConv, List.of());
                history.addAll(tmpConv.getHistory());
            } catch (Exception e) {
                copyRecent(conv.getHistory(), history);
            }
        } else {
            copyRecent(conv.getHistory(), history);
        }
        // 注入 Skill prompt
        history.add(new MessageRecord(Role.USER, prompt, Collections.emptyList()));
        return history;
    }

    private static LlmProvider resolveProvider(SkillDef skill, LlmProvider provider, Config config) {
        String sm = skill.frontmatter().model();
        if (sm == null || sm.isBlank()) return provider;
        Config fc = new Config();
        fc.setProtocol(config.protocol());
        fc.setModel(sm);
        fc.setBaseUrl(config.baseUrl());
        fc.setApiKey(config.apiKey());
        fc.setContextWindow(config.contextWindow());
        fc.setToolTimeout(config.toolTimeout());
        return ProviderFactory.create(fc);
    }

    private static void copyRecent(List<MessageRecord> source, List<MessageRecord> target) {
        int start = Math.max(0, source.size() - 5);
        for (int i = start; i < source.size(); i++) {
            target.add(source.get(i));
        }
    }
}
