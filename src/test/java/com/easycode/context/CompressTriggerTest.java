package com.easycode.context;

import com.easycode.conversation.ConversationMgr;
import com.easycode.config.Config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompressTriggerTest {

    /** 用小窗口 50000：触发线 = 50000 - 33000 = 17000 tokens */
    private static Config smallWindow() {
        return new Config("anthropic", "claude", "https://api.example.com", "sk-test",
                50_000, 30, "system prompt");
    }

    @Test
    void shouldTriggerAutoCompressWhenOverThreshold() {
        Config config = smallWindow();
        ContextManager mgr = new ContextManager(null, config, "test-s1");
        ConversationMgr conv = new ConversationMgr();

        // 灌对话，让估算 > 17000 tokens
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append("第").append(i)
                .append("轮对话内容填充上下文窗口测试数据。\n");
        conv.addUserMessage("分析项目 " + big);              // ~95K chars
        conv.addAssistantMessage("好的，开始分析 " + big);
        conv.addToolResult("t1", big.toString(), false);

        TokenEstimator est = new TokenEstimator();
        long estimated = est.estimate(conv);
        long threshold = config.contextWindow() - Constants.SUMMARY_OUTPUT_RESERVE - Constants.AUTO_SAFETY_MARGIN;
        System.out.println("估算 token: " + estimated + "  阈值: " + threshold);
        assertTrue(estimated > threshold,
            "估算 " + estimated + " 应超过阈值 " + threshold);

        // autoManage：超阈值会尝试摘要，但 provider=null 所以摘要失败
        CompressEvent evt = mgr.autoManage(conv, java.util.List.of());
        System.out.println("结果: " + evt.toDisplay() + " success=" + evt.success());
        assertNotNull(evt);
    }

    @Test
    void shouldOffloadBeforeSummary() {
        Config config = smallWindow();
        ContextManager mgr = new ContextManager(null, config, "test-s2");
        ConversationMgr conv = new ConversationMgr();

        // 先造一个大工具结果（>20KB，触发第 1 层）
        StringBuilder toolResult = new StringBuilder();
        for (int i = 0; i < 3000; i++) toolResult.append("ABCDEFGHIJ\n"); // ~30KB
        conv.addUserMessage("read a file");
        conv.addToolResult("call_big", toolResult.toString(), false);

        // 再加对话让总量超过阈值
        StringBuilder padding = new StringBuilder();
        for (int i = 0; i < 2000; i++) padding.append("上下文填充数据行。\n");
        conv.addAssistantMessage("result " + padding);

        CompressEvent evt = mgr.autoManage(conv, java.util.List.of());
        System.out.println("offload+summary: " + evt.toDisplay() + " replaced=" + evt.replacedCount());

        // 验证大工具结果被替换
        var msg = conv.getHistory().get(1);
        var tr = (com.easycode.conversation.MessageBlock.ToolResultBlock) msg.blocks().get(0);
        assertTrue(tr.content().contains("完整内容已保存至"),
            "预览体应包含落盘路径，实际: " + tr.content().substring(0, Math.min(100, tr.content().length())));
    }

    @Test
    void shouldNotTriggerWhenBelowThreshold() {
        Config config = smallWindow();
        ContextManager mgr = new ContextManager(null, config, "test-s3");
        ConversationMgr conv = new ConversationMgr();
        conv.addUserMessage("hello world");
        conv.addAssistantMessage("hi there");

        TokenEstimator est = new TokenEstimator();
        long estimated = est.estimate(conv);
        long threshold = config.contextWindow() - Constants.SUMMARY_OUTPUT_RESERVE - Constants.AUTO_SAFETY_MARGIN;
        assertTrue(estimated < threshold);

        CompressEvent evt = mgr.autoManage(conv, java.util.List.of());
        assertTrue(evt.success(), "低于阈值不应触发摘要");
    }

    @Test
    void shouldCircuitBreakAfterFailures() {
        Config config = smallWindow();
        ContextManager mgr = new ContextManager(null, config, "test-s4");
        ConversationMgr conv = new ConversationMgr();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append("padding content for overflow.\n");
        conv.addUserMessage("test " + big);
        conv.addToolResult("t1", big.toString(), false);

        // 连续 3 次 autoManage（超阈值 + 无 provider → 每次摘要都失败）
        for (int i = 0; i < 3; i++) {
            mgr.autoManage(conv, java.util.List.of());
        }
        // 第 4 次：熔断后不再尝试摘要，但返回 success（只执行第 1 层）
        CompressEvent evt4 = mgr.autoManage(conv, java.util.List.of());
        assertTrue(evt4.success(), "熔断后跳过摘要，只执行 offload，应成功");
    }

    @Test
    void shouldManualCompactBypassCircuitBreaker() {
        Config config = smallWindow();
        ContextManager mgr = new ContextManager(null, config, "test-s5");
        ConversationMgr conv = new ConversationMgr();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append("padding.\n");
        conv.addUserMessage("test " + big);
        conv.addToolResult("t1", big.toString(), false);

        // 触发 3 次失败进熔断
        for (int i = 0; i < 3; i++) mgr.autoManage(conv, java.util.List.of());

        // 手动压缩绕过熔断（仍无 provider，所以也会失败）
        CompressEvent manEvt = mgr.manualCompact(conv, java.util.List.of());
        assertNotNull(manEvt);
        System.out.println("手动压缩绕过熔断: " + manEvt.toDisplay());
    }
}
