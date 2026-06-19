package com.easycode.context;

import com.easycode.config.Config;
import com.easycode.conversation.ConversationMgr;
import com.easycode.provider.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 上下文管理器——两层压缩总调度（F6, F7, F25-F29）。
 *
 * 三个入口（F37 互斥）：
 * - autoManage: 自动路径，Agent 每轮调用
 * - manualCompact: 手动 /compact 路径
 * - emergencyCompact: 紧急压缩路径（prompt_too_long 触发）
 */
public final class ContextManager {
    private final ReplacementLedger ledger = new ReplacementLedger();
    private final FileTracker fileTracker = new FileTracker();
    private final TokenEstimator estimator = new TokenEstimator();
    private final ReentrantLock sessionLock = new ReentrantLock();

    private final LlmProvider provider;
    private final Config config;
    private final String sessionId;

    private int consecutiveFailures;
    private int knownMessageCount;

    public ContextManager(LlmProvider provider, Config config, String sessionId) {
        this.provider = provider;
        this.config = config;
        this.sessionId = sessionId;
    }

    // ======================== 自动路径 ========================

    /** Agent 每轮开始前调用（F6, F7） */
    public CompressEvent autoManage(ConversationMgr conv, List<JsonNode> tools) {
        sessionLock.lock();
        try {
            long before = estimator.estimate(conv);

            // 第 1 层：轻量预防（始终执行）
            int replaced = Offloader.offloadAndSnip(conv, ledger, SessionManager.toolResultDir());

            // 第 2 层：重量兜底（超阈值时执行）
            long threshold = config.contextWindow() - Constants.SUMMARY_OUTPUT_RESERVE - Constants.AUTO_SAFETY_MARGIN;
            if (estimator.estimate(conv) > threshold && consecutiveFailures < Constants.AUTO_SUMMARY_CIRCUIT_BREAKER) {
                boolean ok = SummaryGenerator.summarize(conv, provider, ledger, fileTracker, tools);
                if (ok) {
                    consecutiveFailures = 0;
                    estimator.reset();
                } else {
                    consecutiveFailures++;
                }
            }

            long after = estimator.estimate(conv);
            knownMessageCount = conv.getHistory().size();
            return CompressEvent.ok(CompressEvent.CompressReason.AUTO, before, after, replaced);
        } finally {
            sessionLock.unlock();
        }
    }

    // ======================== 手动路径 ========================

    /** /compact 命令触发（F22-F24） */
    public CompressEvent manualCompact(ConversationMgr conv, List<JsonNode> tools) {
        sessionLock.lock();
        try {
            long before = estimator.estimate(conv);
        boolean ok = SummaryGenerator.summarize(conv, provider, ledger, fileTracker, tools);
        estimator.reset();  // 先 reset 再估算，否则 anchor 支配 after 值
        long after = estimator.estimate(conv);
            knownMessageCount = conv.getHistory().size();
            return ok ? CompressEvent.ok(CompressEvent.CompressReason.MANUAL, before, after, 0)
                      : CompressEvent.fail(CompressEvent.CompressReason.MANUAL, "摘要生成失败");
        } finally {
            sessionLock.unlock();
        }
    }

    // ======================== 紧急路径 ========================

    /** prompt_too_long 触发（F25, F26） */
    public CompressEvent emergencyCompact(ConversationMgr conv, List<JsonNode> tools) {
        sessionLock.lock();
        try {
            long before = estimator.estimate(conv);

            // 强制第 1 层
            int replaced = Offloader.offloadAndSnip(conv, ledger, SessionManager.toolResultDir());

            // 摘要
            boolean ok = SummaryGenerator.summarize(conv, provider, ledger, fileTracker, tools);
            if (!ok) {
                return CompressEvent.fail(CompressEvent.CompressReason.EMERGENCY, "紧急压缩失败");
            }

            estimator.reset();
            long after = estimator.estimate(conv);
            knownMessageCount = conv.getHistory().size();

            // F25a: 重估算，检查是否低于 contextWindow - manualSafetyMargin
            long maxAllowed = config.contextWindow() - Constants.MANUAL_SAFETY_MARGIN;
            if (after > maxAllowed) {
                return CompressEvent.fail(CompressEvent.CompressReason.EMERGENCY,
                        "压缩后 token(" + after + ") 仍超安全上限(" + maxAllowed + ")");
            }

            return CompressEvent.ok(CompressEvent.CompressReason.EMERGENCY, before, after, replaced);
        } finally {
            sessionLock.unlock();
        }
    }

    // ======================== 文件追踪 ========================

    /** 文件读取成功后记录（F19） */
    public void recordFileRead(String path, String rawContent) {
        fileTracker.record(path, rawContent);
    }

    // ======================== Token 估算 ========================

    public void updateUsage(int inputTokens, int cacheReadTokens, int cacheCreationTokens, int outputTokens) {
        estimator.updateAnchor(inputTokens, cacheReadTokens, cacheCreationTokens, outputTokens);
    }

    public void markRequested(int messageCount) {
        this.knownMessageCount = messageCount;
    }

    // ======================== 重置 ========================

    public void reset() {
        estimator.reset();
        consecutiveFailures = 0;
        knownMessageCount = 0;
    }
}
