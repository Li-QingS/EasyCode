package com.easycode.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;

public final class SessionCleaner {
    private static final int MAX_DAYS = 30;
    private SessionCleaner() {}

    public static void clean(Path sessionsRoot) {
        new Thread(() -> {
            if (!Files.isDirectory(sessionsRoot)) return;
            try (var dirs = Files.list(sessionsRoot)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    String name = dir.getFileName().toString();
                    var ts = SessionContext.parseTimestamp(name);
                    if (ts.isEmpty()) return;
                    if (ts.get().isBefore(LocalDateTime.now().minusDays(MAX_DAYS))) {
                        try (var walk = Files.walk(dir)) {
                            walk.sorted(Comparator.reverseOrder())
                                .forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
                        } catch (IOException ignored) {}
                    }
                });
            } catch (IOException ignored) {}
        }, "session-cleaner").start();
    }
}
