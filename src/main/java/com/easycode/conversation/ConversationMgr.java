package com.easycode.conversation;

import com.easycode.provider.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 会话管理：维护对话历史 */
public final class ConversationMgr {

    private final List<MessageRecord> history = new ArrayList<>();
    private String systemPrompt = "";

    /** 追加用户消息 */
    public void addUserMessage(String content) {
        history.add(new MessageRecord(Role.USER, content));
    }

    /** 追加助手消息 */
    public void addAssistantMessage(String content) {
        history.add(new MessageRecord(Role.ASSISTANT, content));
    }

    /** 返回对话历史的不可变副本 */
    public List<MessageRecord> getHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /** 追加任意消息（含 block 结构） */
    public void addMessage(MessageRecord msg) {
        history.add(msg);
    }

    /** 追加工具调用消息 */
    public void addToolUse(ToolCall call) {
        history.add(new MessageRecord(Role.ASSISTANT, "",
                List.of(new MessageBlock.ToolUseBlock(call.id(), call.name(), call.input()))));
    }

    /** 追加工具调用消息（接受分散参数） */
    public void addToolUse(String toolId, String toolName, JsonNode input) {
        history.add(new MessageRecord(Role.ASSISTANT, "",
                List.of(new MessageBlock.ToolUseBlock(toolId, toolName, input))));
    }

    /** 追加工具结果消息 */
    public void addToolResult(String toolUseId, String content, boolean isError) {
        history.add(new MessageRecord(Role.USER, "",
                List.of(new MessageBlock.ToolResultBlock(toolUseId, content, isError))));
    }

    /** 设置 system prompt，注入到历史第一条 */
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }

    /** Token 数估算：总字符数 / 3 */
    public int estimateTokens() {
        long totalChars = 0;
        for (MessageRecord m : history) {
            totalChars += m.content() != null ? m.content().length() : 0;
            for (MessageBlock b : m.blocks()) {
                if (b instanceof MessageBlock.TextBlock tb) totalChars += tb.text().length();
                else if (b instanceof MessageBlock.ToolResultBlock tr) totalChars += tr.content().length();
            }
        }
        return (int) (totalChars / 3);
    }

    /** 窗口裁剪：保留首条（system prompt）+ 最后一条 user，从前往后删旧消息 */
    public void trimToWindow(int maxTokens) {
        if (history.isEmpty()) return;
        while (estimateTokens() > maxTokens && history.size() > 2) {
            // 保留 index 0（system prompt）和最后一个 user 消息
            int lastUser = history.size() - 1;
            while (lastUser > 0 && history.get(lastUser).role() != Role.USER) lastUser--;
            if (lastUser <= 0) break;
            // 从 index 1 开始删（保留 system），但不删 lastUser
            for (int i = 1; i < history.size(); i++) {
                if (i != lastUser) {
                    history.remove(i);
                    break;
                }
            }
        }
    }
}
