package com.easycode.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import com.easycode.session.SessionContext;
/** 会话 ID 与目录管理（F34, F35）。 */
public final class SessionManager {
    private static final SecureRandom RNG = new SecureRandom();
    private static volatile String sessionId;

    private SessionManager() {}

    /** @deprecated 使用 SessionContext.newSessionId() 获取新格式 ID */
    public static String sessionId() {
        if (sessionId == null) { synchronized (SessionManager.class) {
            if (sessionId == null) sessionId = SessionContext.newSessionId();
        }}
        return sessionId;
    }

    /** 返回工具结果落盘目录，按需创建。 */
    public static Path toolResultDir() {
        return SessionContext.toolResultDir(sessionId());
}
}
