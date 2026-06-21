package com.easycode.skill;

import com.easycode.command.CommandDispatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 热更新轮询线程：每 2 秒检查 skill 目录变更，触发重载 */
public final class SkillWatcher {
    private final SkillLoader skillLoader;
    private final SkillRegistry skillRegistry;
    private final CommandDispatcher dispatcher;
    private final Path projectDir;
    private final Path userDir;
    private final Map<Path, Long> lastModified = new HashMap<>();
    private volatile boolean running = true;

    public SkillWatcher(SkillLoader skillLoader, SkillRegistry skillRegistry,
                        CommandDispatcher dispatcher, Path projectRoot) {
        this.skillLoader = skillLoader;
        this.skillRegistry = skillRegistry;
        this.dispatcher = dispatcher;
        this.projectDir = projectRoot.resolve(".easycode/skills");
        this.userDir = Path.of(System.getProperty("user.home"), ".easycode/skills");
    }

    public void start() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(2000);
                    if (checkChanges()) {
                        reload();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.err.println("[SkillWatcher] 轮询异常: " + e.getMessage());
                }
            }
        }, "skill-watcher");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
    }

    private boolean checkChanges() {
        return dirChanged(projectDir) || dirChanged(userDir);
    }

    private boolean dirChanged(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        long stamp = dirLastModified(dir);
        Long prev = lastModified.get(dir);
        lastModified.put(dir, stamp);
        return prev != null && stamp > prev;
    }

    private long dirLastModified(Path dir) {
        // 递归取目录下所有文件的最大 mtime（目录 mtime 只在增删文件时变，不检测内容修改）
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                .mapToLong(f -> {
                    try { return Files.getLastModifiedTime(f).toMillis(); }
                    catch (IOException e) { return 0; }
                }).max().orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }

    private void reload() {
        try {
            List<SkillFrontmatter> fresh = skillLoader.loadAll();
            skillRegistry.reload(fresh);
            if (dispatcher != null) {
                dispatcher.reloadSkillCommands(fresh);
            }
            System.out.println("[SkillWatcher] Skill 已热更新");
        } catch (Exception e) {
            System.err.println("[SkillWatcher] 重载失败: " + e.getMessage());
        }
    }
}
