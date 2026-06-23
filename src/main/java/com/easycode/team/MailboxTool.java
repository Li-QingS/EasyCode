package com.easycode.team;

import com.easycode.tool.Tool;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 点对点消息工具：仅小组成员可见 */
public class MailboxTool implements Tool {

    private static final ObjectMapper json = new ObjectMapper();
    private final Mailbox mailbox;
    private final NameRegistry nameRegistry;
    private final String memberName;

    public MailboxTool(Mailbox mailbox, NameRegistry nameRegistry, String memberName) {
        this.mailbox = mailbox;
        this.nameRegistry = nameRegistry;
        this.memberName = memberName;
    }

    @Override public String name() { return "team_message"; }

    @Override public String description() {
        return """
            小组成员间点对点消息。支持以下子命令：
            - send: 发送消息给指定成员，参数 to(string,必填)、body(string,必填)、type(string,可选:TEXT|STATUS|ASSIGNMENT，默认TEXT)
            - read: 读取自己的邮箱，参数 unreadOnly(boolean,可选，默认true)
            - broadcast: 广播消息给所有小组成员，参数 body(string,必填)、type(string,可选)
            注意：APPROVAL_REQUEST/APPROVAL_REPLY 类型用于 Lead 审批流程，普通成员使用 TEXT 即可。""";
    }

    @Override public Category category() { return Category.SHELL; }

    @Override public Permission permission() { return Permission.READ_WRITE; }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("action").put("type", "string")
            .put("description", "操作: send|read|broadcast");
        props.putObject("to").put("type", "string");
        props.putObject("body").put("type", "string");
        props.putObject("type").put("type", "string")
            .put("description", "消息类型: TEXT|APPROVAL_REQUEST|APPROVAL_REPLY|STATUS|ASSIGNMENT");
        props.putObject("unreadOnly").put("type", "boolean");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode input) {
        String action = input.has("action") ? input.get("action").asText() : "read";
        try {
            return switch (action) {
                case "send" -> doSend(input);
                case "read" -> doRead(input);
                case "broadcast" -> doBroadcast(input);
                default -> ToolResult.err("team_message", "未知操作: " + action, 0);
            };
        } catch (Exception e) {
            return ToolResult.err("team_message", e.getMessage(), 0);
        }
    }

    private ToolResult doSend(JsonNode input) throws IOException {
        String to = input.has("to") ? input.get("to").asText() : "";
        if (to.isBlank()) return ToolResult.err("team_message", "to 不能为空", 0);
        String body = input.has("body") ? input.get("body").asText() : "";
        if (body.isBlank()) return ToolResult.err("team_message", "body 不能为空", 0);
        MessageType type = parseType(input);
        Path target = nameRegistry.resolve(to);
        if (target == null) return ToolResult.err("team_message", "成员不存在: " + to, 0);
        Message msg = new Message(memberName, body, type);
        Mailbox.send(target, msg);
        return ToolResult.ok("team_message",
            "消息已发送给 " + to + " (id=" + msg.id() + ")", msg.id().length());
    }

    private ToolResult doRead(JsonNode input) throws IOException {
        boolean unreadOnly = !input.has("unreadOnly") || input.get("unreadOnly").asBoolean();
        List<Message> msgs = mailbox.receive(unreadOnly);
        StringBuilder sb = new StringBuilder("邮箱 (" + msgs.size() + " 条):\n");
        for (Message m : msgs) {
            sb.append(formatMessage(m)).append("\n");
        }
        return ToolResult.ok("team_message", sb.toString().trim(), sb.length());
    }

    private ToolResult doBroadcast(JsonNode input) throws IOException {
        String body = input.has("body") ? input.get("body").asText() : "";
        if (body.isBlank()) return ToolResult.err("team_message", "body 不能为空", 0);
        MessageType type = parseType(input);
        Message msg = new Message(memberName, body, type);
        Mailbox.broadcast(msg, nameRegistry);
        return ToolResult.ok("team_message",
            "已广播给 " + nameRegistry.size() + " 个成员", msg.id().length());
    }

    private MessageType parseType(JsonNode input) {
        if (input.has("type") && !input.get("type").asText().isBlank()) {
            try { return MessageType.valueOf(input.get("type").asText().toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        return MessageType.TEXT;
    }

    private String formatMessage(Message m) {
        String readMark = m.read() ? "✓" : "●";
        return String.format("[%s] %s <%s> %s %s",
            m.id(), readMark, m.sender(),
            m.type() != MessageType.TEXT ? "[" + m.type() + "]" : "",
            m.summary());
    }
}
