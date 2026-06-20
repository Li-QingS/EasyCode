package com.easycode.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

/** 会话上下文：ID 生成（新格式 F9）+ 目录管理（F10）。替代旧 SessionManager。 */
public final class SessionContext {
    private static final SecureRandom RNG = new SecureRandom();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern ID_PATTERN = Pattern.compile("^(\\d{8}-\\d{6})-[0-9a-fA-F]{4}$");

    private SessionContext() {}

    /** F9: 生成新 session ID: YYYYMMDD-HHMMSS-xxxx */
    public static String newSessionId() {
        String ts = LocalDateTime.now().format(FMT);
        String rand = String.format("%04x", RNG.nextInt(0xFFFF + 1));
        return ts + "-" + rand;
    }

    /** F10: .easycode/sessions/<id>/ */
    public static Path sessionDir(String sessionId) {
        return Path.of(".easycode", "sessions", sessionId);
    }

    /** ch08 兼容: tool-results 子目录 */
    public static Path toolResultDir(String sessionId) {
        Path dir = sessionDir(sessionId).resolve("tool-results");
        if (!Files.isDirectory(dir)) {
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
        }
        return dir;
    }

    /** conversation.jsonl 路径 */
    public static Path jsonlPath(String sessionId) {
        return sessionDir(sessionId).resolve("conversation.jsonl");
    }

    /** 从 session ID 中解析时间戳（用于排序/清理） */
    public static Optional<LocalDateTime> parseTimestamp(String sessionId) {
        var m = ID_PATTERN.matcher(sessionId);
        if (!m.matches()) return Optional.empty();
        try {
            return Optional.of(LocalDateTime.parse(m.group(1), FMT));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
