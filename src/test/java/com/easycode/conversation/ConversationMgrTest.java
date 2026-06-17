package com.easycode.conversation;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMgrTest {

    @Test
    void shouldStartWithEmptyHistory() {
        ConversationMgr mgr = new ConversationMgr();
        assertTrue(mgr.getHistory().isEmpty());
    }

    @Test
    void shouldAddUserMessage() {
        ConversationMgr mgr = new ConversationMgr();
        mgr.addUserMessage("你好");
        List<MessageRecord> history = mgr.getHistory();
        assertEquals(1, history.size());
        assertEquals(Role.USER, history.get(0).role());
        assertEquals("你好", history.get(0).content());
    }

    @Test
    void shouldPreserveMessageOrder() {
        ConversationMgr mgr = new ConversationMgr();
        mgr.addUserMessage("问题1");
        mgr.addAssistantMessage("回答1");
        mgr.addUserMessage("问题2");
        mgr.addAssistantMessage("回答2");

        List<MessageRecord> history = mgr.getHistory();
        assertEquals(4, history.size());
        assertEquals(Role.USER, history.get(0).role());
        assertEquals("问题1", history.get(0).content());
        assertEquals(Role.ASSISTANT, history.get(1).role());
        assertEquals("回答1", history.get(1).content());
        assertEquals(Role.USER, history.get(2).role());
        assertEquals("问题2", history.get(2).content());
        assertEquals(Role.ASSISTANT, history.get(3).role());
        assertEquals("回答2", history.get(3).content());
    }

    @Test
    void shouldReturnUnmodifiableList() {
        ConversationMgr mgr = new ConversationMgr();
        mgr.addUserMessage("测试");
        List<MessageRecord> history = mgr.getHistory();
        assertThrows(UnsupportedOperationException.class,
                () -> history.add(new MessageRecord(Role.USER, "x")));
    }

    @Test
    void getHistoryShouldReturnCopy() {
        ConversationMgr mgr = new ConversationMgr();
        mgr.addUserMessage("第一条");
        List<MessageRecord> snapshot = mgr.getHistory();
        mgr.addAssistantMessage("第二条");
        assertEquals(1, snapshot.size());
        assertEquals(2, mgr.getHistory().size());
    }
}
