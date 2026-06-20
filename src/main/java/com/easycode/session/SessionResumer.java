package com.easycode.session;

import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 会话恢复（F17-F24）：扫描 + 恢复。 */
public final class SessionResumer {
    private static final ObjectMapper json = new ObjectMapper();

    private SessionResumer() {}

    public record SessionSummary(String id, String title, long lastModified, String model, long fileSize) {}

    /** 扫描所有有效会话（F18），按 mtime 倒序 */
    public static List<SessionSummary> scanAll(Path sessionsRoot) {
        List<SessionSummary> result = new ArrayList<>();
        if (!Files.isDirectory(sessionsRoot)) return result;
        try (var dirs = Files.list(sessionsRoot)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path jl = dir.resolve("conversation.jsonl");
                if (!Files.isRegularFile(jl)) return;
                String sid = dir.getFileName().toString();
                if (SessionContext.parseTimestamp(sid).isEmpty()) return; // skip old format
                try {
                    long mtime = Files.getLastModifiedTime(jl).toMillis();
                    long size = Files.size(jl);
                    String title = readFirstUserContent(jl);
                    String model = readFirstModel(jl);
                    result.add(new SessionSummary(sid, title, mtime, model, size));
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        result.sort(Comparator.comparingLong(SessionSummary::lastModified).reversed());
        return result;
    }

    private static String readFirstUserContent(Path jsonl) {
        try (BufferedReader r = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonNode node = json.readTree(line);
                if ("user".equals(node.path("role").asText()) && node.has("content")) {
                    String c = node.get("content").asText();
                    return c.length() > 50 ? c.substring(0, 47) + "..." : c;
                }
            }
        } catch (Exception ignored) {}
        return "(无标题)";
    }

    private static String readFirstModel(Path jsonl) {
        try (BufferedReader r = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            if (line != null) {
                JsonNode node = json.readTree(line);
                if (node.has("model")) return node.get("model").asText();
            }
        } catch (Exception ignored) {}
        return "?";
    }

    /** 恢复会话（F21） */
    public static List<MessageRecord> resume(Path jsonl) throws IOException {
        List<MessageRecord> msgs = new ArrayList<>();
        boolean afterCompact = false;
        int lastCompactIdx = -1;

        // 第一遍：找到最后一个 compact 标记
        try (BufferedReader r = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line; int idx = 0;
            while ((line = r.readLine()) != null) {
                try {
                    JsonNode node = json.readTree(line);
                    if ("compact".equals(node.path("type").asText())) {
                        lastCompactIdx = idx;
                        msgs.clear();
                    } else if (node.has("role")) {
                        msgs.add(parseMessage(node));
                    }
                } catch (Exception e) { /* skip bad line */ }
                idx++;
            }
        }

        // F21-3: 截断孤立 tool_calls
        if (!msgs.isEmpty()) {
            MessageRecord last = msgs.get(msgs.size() - 1);
            if (last.role() == Role.ASSISTANT && hasToolCalls(last)) {
                msgs.remove(msgs.size() - 1);
            }
        }
        return msgs;
    }

    private static MessageRecord parseMessage(JsonNode node) {
        Role role = "assistant".equals(node.path("role").asText()) ? Role.ASSISTANT : Role.USER;
        String content = node.path("content").asText(null);
        List<MessageBlock> blocks = new ArrayList<>();
        if (node.has("tool_calls")) {
            for (JsonNode tc : node.get("tool_calls")) {
                blocks.add(new MessageBlock.ToolUseBlock(
                    tc.path("id").asText(), tc.path("name").asText(), tc.path("input")));
            }
        }
        if (node.has("tool_results")) {
            for (JsonNode tr : node.get("tool_results")) {
                blocks.add(new MessageBlock.ToolResultBlock(
                    tr.path("tool_use_id").asText(), tr.path("content").asText(""), tr.path("is_error").asBoolean(false)));
            }
        }
        return new MessageRecord(role, content != null ? content : "", blocks);
    }

    private static boolean hasToolCalls(MessageRecord msg) {
        for (var b : msg.blocks()) if (b instanceof MessageBlock.ToolUseBlock) return true;
        return false;
    }

    /** 相对时间（F20） */
    public static String relativeTime(long epochMillis) {
        long ago = System.currentTimeMillis() - epochMillis;
        long mins = ago / 60000;
        if (mins < 1) return "刚刚";
        if (mins < 60) return mins + " 分钟前";
        long hours = mins / 60;
        if (hours < 24) return hours + " 小时前";
        long days = hours / 24;
        if (days < 30) return days + " 天前";
        return (days / 30) + " 个月前";
    }
}
