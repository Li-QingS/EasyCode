package com.easycode.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 点对点邮箱：文件 + 锁并发 */
public class Mailbox {

    private static final ObjectMapper json = new ObjectMapper();
    private static final int MAX_RETRIES = 10;
    private static final long RETRY_DELAY_MS = 100;
    private static final long LOCK_EXPIRE_MS = 30_000;

    private final Path mailboxFile;

    public Mailbox(Path mailboxFile) {
        this.mailboxFile = mailboxFile;
    }

    /** 发送消息到指定接收方邮箱 */
    public static void send(Path targetMailbox, Message msg) throws IOException {
        withLockVoid(targetMailbox, () -> {
            String line = json.writeValueAsString(toMap(msg)) + "\n";
            Files.writeString(targetMailbox, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        });
    }

    /** 读取自己的邮箱 */
    public List<Message> receive(boolean unreadOnly) throws IOException {
        return withLock(mailboxFile, () -> readMessages(unreadOnly));
    }

    /** 标记消息已读 */
    public void markRead(String messageId) throws IOException {
        withLockVoid(mailboxFile, () -> {
            List<Map<String, Object>> all = readRawMessages();
            boolean found = false;
            for (Map<String, Object> m : all) {
                if (messageId.equals(m.get("id"))) {
                    m.put("read", true);
                    found = true;
                }
            }
            if (found) writeRawMessages(all);
        });
    }

    /** 广播消息给所有已注册成员 */
    public static void broadcast(Message msg, NameRegistry registry) throws IOException {
        for (String name : registry.names()) {
            Path target = registry.resolve(name);
            if (target != null) {
                send(target, msg);
            }
        }
    }

    // ========== 锁文件机制 ==========

    private static Path lockPath(Path file) {
        return file.resolveSibling(file.getFileName() + ".lock");
    }

    @FunctionalInterface
    private interface LockedAction<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    private interface LockedVoidAction {
        void run() throws IOException;
    }

    private static void withLockVoid(Path file, LockedVoidAction action) throws IOException {
        withLock(file, () -> { action.run(); return null; });
    }

    private static <T> T withLock(Path file, LockedAction<T> action) throws IOException {
        Path lock = lockPath(file);
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                Files.createFile(lock);
                try {
                    return action.run();
                } finally {
                    Files.deleteIfExists(lock);
                }
            } catch (FileAlreadyExistsException e) {
                // 锁已存在，检查是否过期
                try {
                    FileTime mtime = Files.getLastModifiedTime(lock);
                    long age = System.currentTimeMillis() - mtime.to(TimeUnit.MILLISECONDS);
                    if (age > LOCK_EXPIRE_MS) {
                        Files.deleteIfExists(lock); // 过期抢占
                        continue;
                    }
                } catch (IOException ignored) {}
                // 等待重试
                try { Thread.sleep(RETRY_DELAY_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new IOException("无法获取邮箱锁: " + file + " (重试 " + MAX_RETRIES + " 次后放弃)");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readRawMessages() throws IOException {
        if (!Files.exists(mailboxFile) || Files.size(mailboxFile) == 0) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (String line : Files.readAllLines(mailboxFile)) {
            if (line.isBlank()) continue;
            try {
                messages.add(json.readValue(line, Map.class));
            } catch (IOException ignored) {}
        }
        return messages;
    }

    private void writeRawMessages(List<Map<String, Object>> messages) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            sb.append(json.writeValueAsString(m)).append("\n");
        }
        Files.writeString(mailboxFile, sb.toString());
    }

    @SuppressWarnings("unchecked")
    private List<Message> readMessages(boolean unreadOnly) throws IOException {
        List<Message> result = new ArrayList<>();
        for (Map<String, Object> m : readRawMessages()) {
            Message msg = fromMap(m);
            if (!unreadOnly || !msg.read()) {
                result.add(msg);
            }
        }
        return result;
    }

    // ========== JSON 转换 ==========

    private static Map<String, Object> toMap(Message msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", msg.id());
        m.put("sender", msg.sender());
        m.put("body", msg.body());
        m.put("type", msg.type().name());
        m.put("timestamp", msg.timestamp());
        m.put("read", msg.read());
        m.put("summary", msg.summary());
        return m;
    }

    private static Message fromMap(Map<String, Object> m) {
        return new Message(
            (String) m.get("id"),
            (String) m.get("sender"),
            (String) m.get("body"),
            MessageType.valueOf((String) m.get("type")),
            ((Number) m.get("timestamp")).longValue(),
            (boolean) m.getOrDefault("read", false),
            (String) m.get("summary")
        );
    }
}
