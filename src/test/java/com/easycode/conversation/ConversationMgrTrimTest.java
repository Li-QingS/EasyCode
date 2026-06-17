package com.easycode.conversation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMgrTrimTest {
    @Test
    void shouldTrimToWindow() {
        ConversationMgr mgr = new ConversationMgr();
        // 添加大量长消息
        String longText = "x".repeat(200);
        for (int i = 0; i < 20; i++) {
            mgr.addUserMessage(longText);
            mgr.addAssistantMessage(longText);
        }
        int before = mgr.estimateTokens();
        mgr.trimToWindow(100); // 非常小的窗口
        int after = mgr.estimateTokens();
        assertTrue(after <= before);
        assertTrue(mgr.getHistory().size() < 40);
    }

    @Test
    void shouldEstimateTokens() {
        ConversationMgr mgr = new ConversationMgr();
        mgr.addUserMessage("hello world"); // 11 chars + overhead
        mgr.addAssistantMessage("hi");
        int tokens = mgr.estimateTokens();
        assertTrue(tokens > 0);
        assertTrue(tokens < 20); // ~22/3 ≈ 7 tokens
    }
}
