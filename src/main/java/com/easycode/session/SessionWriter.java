package com.easycode.session;

import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** JSONL 追加写入器（F13-F16）。线程安全。 */
public final class SessionWriter implements Closeable {
    private static final ObjectMapper json = new ObjectMapper();
    private final BufferedWriter writer;
    private final FileOutputStream fos;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean firstLine = true;

    public SessionWriter(Path jsonlPath) {
        try {
            java.nio.file.Files.createDirectories(jsonlPath.getParent());
            fos = new FileOutputStream(jsonlPath.toFile(), true); // append mode
            writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to open session writer: " + jsonlPath, e);
        }
    }

    public void append(MessageRecord msg, String model) {
        lock.lock();
        try {
            String line = serialize(msg, model);
            writer.write(line);
            writer.newLine();
            writer.flush();
            fos.getFD().sync();
        } catch (Exception e) {
            System.err.println("[session] write failed: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void appendCompactMarker() {
        lock.lock();
        try {
            writer.write("{\"type\":\"compact\",\"ts\":" + (System.currentTimeMillis() / 1000) + "}");
            writer.newLine();
            writer.flush();
            fos.getFD().sync();
        } catch (Exception e) {
            System.err.println("[session] compact marker write failed: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @Override public void close() {
        lock.lock();
        try { writer.close(); } catch (Exception ignored) {}
        finally { lock.unlock(); }
    }

    private String serialize(MessageRecord msg, String model) {
        ObjectNode node = json.createObjectNode();
        node.put("role", msg.role().name().toLowerCase());
        if (msg.content() != null && !msg.content().isBlank()) {
            node.put("content", msg.content());
        }
        node.put("ts", System.currentTimeMillis() / 1000);
        if (firstLine && model != null) {
            node.put("model", model);
            firstLine = false;
        }
        // tool_calls
        ArrayNode tcArr = null;
        for (var b : msg.blocks()) {
            if (b instanceof MessageBlock.ToolUseBlock tu) {
                if (tcArr == null) tcArr = json.createArrayNode();
                ObjectNode tc = json.createObjectNode();
                tc.put("id", tu.id());
                tc.put("name", tu.name());
                tc.set("input", tu.input());
                tcArr.add(tc);
            }
        }
        if (tcArr != null) node.set("tool_calls", tcArr);
        // tool_results
        ArrayNode trArr = null;
        for (var b : msg.blocks()) {
            if (b instanceof MessageBlock.ToolResultBlock tr) {
                if (trArr == null) trArr = json.createArrayNode();
                ObjectNode tro = json.createObjectNode();
                tro.put("tool_use_id", tr.toolUseId());
                tro.put("content", tr.content());
                tro.put("is_error", tr.isError());
                trArr.add(tro);
            }
        }
        if (trArr != null) node.set("tool_results", trArr);
        return node.toString();
    }
}
