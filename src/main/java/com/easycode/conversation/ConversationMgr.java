package com.easycode.conversation;

import com.easycode.provider.ToolCall;
import com.easycode.context.ReplacementLedger;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** 会话管理：维护对话历史 */
public final class ConversationMgr {

    private final List<MessageRecord> history = new ArrayList<>();
    private final Consumer<MessageRecord> onAppend;
    private final Consumer<List<MessageRecord>> onReplace;
    private String systemPrompt = "";

    public ConversationMgr() { this(null, null); }
    public ConversationMgr(Consumer<MessageRecord> onAppend, Consumer<List<MessageRecord>> onReplace) {
        this.onAppend = onAppend; this.onReplace = onReplace;
    }

    // ---- 追加 ----

    public void addUserMessage(String content) {
        var msg = new MessageRecord(Role.USER, content);
        history.add(msg);
        if (onAppend != null) onAppend.accept(msg);
    }

    public void addAssistantMessage(String content) {
        var msg = new MessageRecord(Role.ASSISTANT, content);
        history.add(msg);
        if (onAppend != null) onAppend.accept(msg);
    }

    public List<MessageRecord> getHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public void addMessage(MessageRecord msg) {
        history.add(msg);
        if (onAppend != null) onAppend.accept(msg);
    }

    public void addToolUse(ToolCall call) {
        var msg = new MessageRecord(Role.ASSISTANT, "",
                List.of(new MessageBlock.ToolUseBlock(call.id(), call.name(), call.input())));
        history.add(msg);
        if (onAppend != null) onAppend.accept(msg);
    }

    public void addToolUse(String toolId, String toolName, JsonNode input) {
        var msg = new MessageRecord(Role.ASSISTANT, "",
                List.of(new MessageBlock.ToolUseBlock(toolId, toolName, input)));
        history.add(msg);
        if (onAppend != null) onAppend.accept(msg);
    }

    public void addToolResult(String toolUseId, String content, boolean isError) {
        var msg = new MessageRecord(Role.USER, "",
                List.of(new MessageBlock.ToolResultBlock(toolUseId, content, isError)));
        history.add(msg);
        if (onAppend != null) onAppend.accept(msg);
    }

    public void setSystemPrompt(String prompt) { this.systemPrompt = prompt; }

    // ---- 修改 ----

    public void replaceMessage(int index, MessageRecord msg) {
        if (index >= 0 && index < history.size()) history.set(index, msg);
    }

    public void replaceAll(List<MessageRecord> newHistory) {
        history.clear();
        history.addAll(newHistory);
        if (onReplace != null) onReplace.accept(newHistory);
    }

    /** ch08: 结合 ReplacementLedger 组装消息列表 */
    public List<MessageRecord> assembleMessages(ReplacementLedger ledger) {
        List<MessageRecord> result = new ArrayList<>();
        for (MessageRecord msg : getHistory()) {
            List<MessageBlock> newBlocks = new ArrayList<>();
            boolean changed = false;
            for (MessageBlock b : msg.blocks()) {
                if (b instanceof MessageBlock.ToolResultBlock tr) {
                    String replacement = ledger.getReplacement(tr.toolUseId());
                    if (replacement != null) {
                        newBlocks.add(new MessageBlock.ToolResultBlock(tr.toolUseId(), replacement, tr.isError()));
                        changed = true;
                    } else { newBlocks.add(b); }
                } else { newBlocks.add(b); }
            }
            result.add(changed ? new MessageRecord(msg.role(), msg.content(), newBlocks) : msg);
        }
        return result;
    }

    /** 修复 role 交替违规——防止 USER→USER 连续导致 API 截断返回空文本 */
    public void fixRoleAlternation() {
        if (history.size() < 2) return;
        for (int i = 1; i < history.size(); i++) {
            Role prev = history.get(i - 1).role();
            Role curr = history.get(i).role();
            if (prev == Role.USER && curr == Role.USER) {
                // 合并两条连续 USER 消息，而非插入无意义的占位消息
                MessageRecord a = history.get(i - 1);
                MessageRecord b = history.get(i);
                String mergedContent = joinContent(a.content(), b.content());
                List<MessageBlock> mergedBlocks = new ArrayList<>(a.blocks());
                mergedBlocks.addAll(b.blocks());
                history.set(i - 1, new MessageRecord(Role.USER, mergedContent, mergedBlocks));
                history.remove(i);
                i--; // 重新检查当前位置
            } else if (prev == Role.ASSISTANT && curr == Role.ASSISTANT) {
                MessageRecord a = history.get(i - 1);
                MessageRecord b = history.get(i);
                String mergedContent = joinContent(a.content(), b.content());
                List<MessageBlock> mergedBlocks = new ArrayList<>(a.blocks());
                mergedBlocks.addAll(b.blocks());
                history.set(i - 1, new MessageRecord(Role.ASSISTANT, mergedContent, mergedBlocks));
                history.remove(i);
                i--;
            }
        }
    }

    private static String joinContent(String a, String b) {
        if (a == null || a.isBlank()) return b != null ? b : "";
        if (b == null || b.isBlank()) return a;
        return a + "\n\n" + b;
    }

    // ---- 估算与裁剪 ----

    public int estimateTokens() {
        long totalChars = 0;
        for (MessageRecord m : history) {
            totalChars += m.content() != null ? m.content().length() : 0;
            for (MessageBlock b : m.blocks()) {
                if (b instanceof MessageBlock.TextBlock tb) totalChars += tb.text().length();
                else if (b instanceof MessageBlock.ToolResultBlock tr) totalChars += tr.content().length();
            }
        }
        return (int)(totalChars / 3);
    }

    public void trimToWindow(int maxTokens) {
        if (history.isEmpty()) return;
        while (estimateTokens() > maxTokens && history.size() > 2) {
            int lastUser = history.size() - 1;
            while (lastUser > 0 && history.get(lastUser).role() != Role.USER) lastUser--;
            if (lastUser <= 0) break;
            for (int i = 1; i < history.size(); i++) {
                if (i != lastUser) { history.remove(i); break; }
            }
        }
    }
}
