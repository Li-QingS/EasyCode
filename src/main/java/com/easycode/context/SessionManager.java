package com.easycode.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/** 会话 ID 与目录管理（F34, F35）。 */
public final class SessionManager {
    private static final SecureRandom RNG = new SecureRandom();
    private static volatile String sessionId;

    private SessionManager() {}

    /** 生成或返回已生成的会话 ID，格式为 <unix_ts>-<6位随机hex>。 */
    public static String sessionId() {
        if (sessionId == null) {
            synchronized (SessionManager.class) {
                if (sessionId == null) {
                    long ts = System.currentTimeMillis() / 1000;
                    String rand = String.format("%06x", RNG.nextInt(0xFFFFFF + 1));
                    sessionId = ts + "-" + rand;
                }
            }
        }
        return sessionId;
    }

    /** 返回工具结果落盘目录，按需创建。 */
    public static Path toolResultDir() {
        Path dir = Path.of(".EasyCode", "sessions", sessionId(), "tool-results");
        if (!Files.isDirectory(dir)) {
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
        }
        return dir;
    }
}
