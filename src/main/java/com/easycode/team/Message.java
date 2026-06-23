package com.easycode.team;

import java.util.UUID;

/** 点对点消息 */
public record Message(
    String id,
    String sender,
    String body,
    MessageType type,
    long timestamp,
    boolean read,
    String summary
) {
    public Message {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString().substring(0, 8);
        if (sender == null) sender = "unknown";
        if (body == null) body = "";
        if (type == null) type = MessageType.TEXT;
        if (timestamp <= 0) timestamp = System.currentTimeMillis();
        if (summary == null || summary.isBlank()) {
            summary = body.length() > 100 ? body.substring(0, 100) + "..." : body;
        }
    }

    /** 便捷构造：自动补 id/timestamp/summary，默认未读 */
    public Message(String sender, String body, MessageType type) {
        this(UUID.randomUUID().toString().substring(0, 8), sender, body, type,
            System.currentTimeMillis(), false, null);
    }

    /** 创建已读/摘要自定义的副本 */
    public Message withRead(boolean isRead) {
        return new Message(id, sender, body, type, timestamp, isRead, summary);
    }
}
