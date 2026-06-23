package com.easycode.context;

import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.provider.LlmProvider;
import com.easycode.provider.Request;
import com.easycode.provider.StreamHandler;
import com.easycode.provider.System;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 第 2 层压缩（F7-F12）：LLM 全量摘要 + PTL 重试策略。
 */
public final class SummaryGenerator {

    private SummaryGenerator() {}

    /**
     * @return true 摘要成功
     */
    public static boolean summarize(ConversationMgr conv, LlmProvider provider,
            ReplacementLedger ledger, FileTracker tracker, List<JsonNode> tools) {
        var history = conv.getHistory();
        if (history.size() <= Constants.KEEP_RECENT_MESSAGE_MIN) return false;

        // 1. 保留近期原文（F11, F12）
        int keepFrom = findKeepIndex(history);
        if (keepFrom <= 1) return false;

        List<MessageRecord> toSummarize = new ArrayList<>(history.subList(0, keepFrom));
        List<MessageRecord> toKeep = new ArrayList<>(history.subList(keepFrom, history.size()));

        // 2. 构建摘要 prompt（F8-F10）
        String prompt = buildSummaryPrompt(toSummarize);

        // 3. 调用 LLM 生成摘要（不传 tools，F8）
        String rawSummary;
        try {
            rawSummary = callLlmForSummary(provider, prompt);
        } catch (Exception e) {
            return false;
        }

        if (rawSummary == null || rawSummary.isBlank()) return false;

        // 4. 提取 <summary> 部分（F9）
        String summary = extractSummary(rawSummary);
        if (summary.isBlank()) summary = rawSummary;

        // 5. 构建新历史：摘要(USER) + 恢复段(USER合并) + 边界消息(ASSISTANT) + 近期原文
        //    保证 role 交替——边界 ASSISTANT 消息隔开摘要与原文
        List<MessageRecord> newHistory = new ArrayList<>();
        String recoveryText = RecoveryBuilder.buildAsString(tracker, tools);
        newHistory.add(new MessageRecord(Role.USER, summary + "\n\n" + recoveryText));
        // 插入 ASSISTANT 边界消息保证 role 交替
        newHistory.add(new MessageRecord(Role.ASSISTANT,
            "已理解。以下是历史摘要，如需文件细节请重新读取，不要根据摘要脑补代码。"));
        newHistory.addAll(toKeep);

        conv.replaceAll(newHistory);
        return true;
    }

    // ---------- PTL 重试（F27） ----------

    /**
     * 带 PTL 重试的摘要调用。最多试 3+ 次（3 次直接重试，之后按 20% 比例丢消息组）。
     */
    private static String callLlmForSummary(LlmProvider provider, String prompt) throws Exception {
        // 构建初始消息
        List<MessageRecord> messages = List.of(new MessageRecord(Role.USER, prompt));
        var sys = new System("你是一个对话摘要助手。只输出文本，禁止调用任何工具。", "");

        for (int attempt = 0; attempt <= Constants.PTL_DIRECT_RETRY_MAX + 5; attempt++) {
            var req = new Request(messages, List.of(), sys, "");
            CompletableFuture<String> future = new CompletableFuture<>();
            try {
                provider.chatStream(req, new StreamHandler() {
                    private final StringBuilder text = new StringBuilder();
                    @Override public void onToken(String token) { text.append(token); }
                    @Override public void onComplete() { future.complete(text.toString().trim()); }
                    @Override public void onError(Exception e) { future.completeExceptionally(e); }
                    @Override public void onUsage(int i, int o, int cw, int cr) {}
                });
                return future.get(Constants.SUMMARY_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (!isPromptTooLong(e) && attempt < Constants.PTL_DIRECT_RETRY_MAX) {
                    continue; // 一般错误，重试
                }
                if (isPromptTooLong(e) && messages.size() > 1) {
                    // PTL：丢消息组
                    int drop = Math.max(1, (int) Math.ceil(messages.size() * Constants.PTL_DROP_RATIO));
                    messages = messages.subList(drop, messages.size());
                    if (messages.isEmpty()) throw e;
                    continue;
                }
                throw e;
            }
        }
        throw new RuntimeException("Summary failed after all retries");
    }

    private static boolean isPromptTooLong(Throwable e) {
        if (e == null) return false;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("prompt_too_long") || msg.contains("prompt too long")
            || msg.contains("提示词过长") || msg.contains("token") && msg.contains("exceed"))
            return true;
        return isPromptTooLong(e.getCause());
    }

    // ---------- 近期原文保留（F11, F12） ----------

    private static int findKeepIndex(List<MessageRecord> history) {
        long tokens = 0;
        int msgCount = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            tokens += TokenEstimator.messageCharCount(history.get(i)) / Constants.ESTIMATE_CHARS_PER_TOKEN;
            msgCount++;
            if (tokens >= Constants.KEEP_RECENT_TOKEN_MIN && msgCount >= Constants.KEEP_RECENT_MESSAGE_MIN) {
                // F12：不拆分 tool_use/tool_result 对
                int adjusted = ensureNotSplittingPair(history, i);
                return adjusted;
            }
        }
        return Math.max(0, history.size() - Constants.KEEP_RECENT_MESSAGE_MIN);
    }

    /** 如果截断点在 tool_result 上，向前推到 tool_use 之前（F12） */
    private static int ensureNotSplittingPair(List<MessageRecord> history, int cutIndex) {
        if (cutIndex >= history.size()) return cutIndex;
        MessageRecord msg = history.get(cutIndex);
        // 如果最前一条保留消息是 tool_result 的 USER 消息，向前推到前一条 assistant（tool_use）之前
        if (msg.role() == Role.USER && hasToolResult(msg)) {
            // 找前一条 assistant
            for (int j = cutIndex - 1; j >= 0; j--) {
                if (history.get(j).role() == Role.ASSISTANT && hasToolUse(history.get(j))) {
                    return j; // 从 tool_use 开始保留
                }
            }
        }
        return cutIndex;
    }

    private static boolean hasToolResult(MessageRecord msg) {
        for (MessageBlock b : msg.blocks()) {
            if (b instanceof MessageBlock.ToolResultBlock) return true;
        }
        return false;
    }

    private static boolean hasToolUse(MessageRecord msg) {
        for (MessageBlock b : msg.blocks()) {
            if (b instanceof MessageBlock.ToolUseBlock) return true;
        }
        return false;
    }

    // ---------- 摘要 prompt 构造（F8-F10） ----------

    private static String buildSummaryPrompt(List<MessageRecord> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("请对以下对话历史生成结构化摘要。\n\n");
        sb.append("步骤：\n");
        sb.append("1. 先在 <analysis> 标签内写分析草稿\n");
        sb.append("2. 再在 <summary> 标签内写正式摘要（草稿用完即丢，只保留正式摘要）\n\n");
        sb.append("正式摘要必须按以下 9 部分结构组织：\n");
        sb.append("① 主要请求和意图\n");
        sb.append("② 关键技术概念\n");
        sb.append("③ 文件和代码段\n");
        sb.append("④ 错误和修复\n");
        sb.append("⑤ 问题解决过程\n");
        sb.append("⑥ 所有用户消息原文（逐条保留）\n");
        sb.append("⑦ 待办任务\n");
        sb.append("⑧ 当前工作（最详细的一段，覆盖正在做什么、停在哪一步）\n");
        sb.append("⑨ 可能的下一步\n\n");
        sb.append("注意：本请求禁止调用任何工具，只输出文本。\n\n");
        sb.append("=== 对话历史 ===\n\n");

        for (MessageRecord m : messages) {
            sb.append(formatMessage(m));
        }
        return sb.toString();
    }

    private static String formatMessage(MessageRecord m) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(m.role()).append("]: ");
        if (m.content() != null && !m.content().isBlank()) {
            sb.append(m.content()).append("\n");
        }
        for (var b : m.blocks()) {
            if (b instanceof MessageBlock.ToolUseBlock tu) {
                sb.append("  [调用工具: ").append(tu.name()).append("]\n");
            } else if (b instanceof MessageBlock.ToolResultBlock tr) {
                String content = tr.content();
                if (content.length() > 800) content = content.substring(0, 800) + "...(截断)";
                sb.append("  [工具结果: ").append(content).append("]\n");
            }
        }
        return sb.toString();
    }

    /** 从 LLM 返回的文本中提取 <summary>...</summary> 的内容（F9） */
    private static String extractSummary(String text) {
        int start = text.indexOf("<summary>");
        int end = text.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return text.substring(start + 9, end).trim();
        }
        // 如果没有标签，尝试忽略 <analysis> 部分
        int analysisEnd = text.indexOf("</analysis>");
        if (analysisEnd >= 0) {
            return text.substring(analysisEnd + 12).trim();
        }
        return text;
    }
}
