package com.easycode.conversation;

import java.util.Collections;
import java.util.List;

/** 单条对话消息：role + 文本摘要 + 结构化 block 列表 */
public record MessageRecord(Role role, String content, List<MessageBlock> blocks) {

    public MessageRecord(Role role, String content) {
        this(role, content, List.of());
    }

    public MessageRecord(Role role, String content, List<MessageBlock> blocks) {
        this.role = role;
        this.content = content;
        this.blocks = Collections.unmodifiableList(blocks);
    }
}
