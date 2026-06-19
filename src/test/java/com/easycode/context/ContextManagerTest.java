package com.easycode.context;

import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.config.Config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    @Test void shouldEstimateTokens() {
        var conv = new ConversationMgr();
        conv.addUserMessage("Hello world");
        conv.addAssistantMessage("Hi there!");
        var estimator = new TokenEstimator();
        long est = estimator.estimate(conv);
        assertTrue(est > 0);
    }

    @Test void shouldAnchorToLastApiTokens() {
        var estimator = new TokenEstimator();
        estimator.updateAnchor(1000, 200, 0, 300);
        assertEquals(1500, estimator.getAnchor());
    }

    @Test void shouldCreateManager() {
        Config config = new Config("anthropic", "claude", "https://api.example.com", "sk-test", 128_000, 30, "sp");
        ContextManager mgr = new ContextManager(null, config, "test-session");
        assertDoesNotThrow(mgr::reset);
    }

    @Test void shouldNotCrashOnEmptyConversation() {
        Config config = new Config("anthropic", "claude", "https://api.example.com", "sk-test", 128_000, 30, "sp");
        ContextManager mgr = new ContextManager(null, config, "test-session");
        var conv = new ConversationMgr();
        var result = mgr.autoManage(conv, java.util.List.of());
        assertTrue(result.success());
    }

    @Test void shouldRecordFileRead() {
        Config config = new Config("anthropic", "claude", "https://api.example.com", "sk-test");
        ContextManager mgr = new ContextManager(null, config, "test-session");
        assertDoesNotThrow(() -> mgr.recordFileRead("/test/path", "content"));
    }
}
