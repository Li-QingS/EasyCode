package com.easycode.subagent;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Worktree 全生命周期管理：创建、变更检测、清理、过期回收 */
public final class WorktreeManager {

    private static final Pattern VALID_SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final String WORKTREES_DIR = ".easycode/worktrees";
    private static final String BRANCH_PREFIX = "easycode/";

    private final Path projectRoot;

    public WorktreeManager(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /** 将 Agent 名称转为安全 slug */
    public static String slug(String name) {
        if (name == null || name.isBlank()) return "agent";
        String s = name.toLowerCase()
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
        if (s.isEmpty()) return "agent";
        if (s.length() > 64) s = s.substring(0, 64);
        return s;
    }

    /** 校验 slug 是否合法 */
    public static boolean isValidSlug(String slug) {
        if (slug == null || slug.isBlank()) return false;
        if (slug.equals(".") || slug.equals("..")) return false;
        return VALID_SLUG.matcher(slug).matches();
    }

    /** 创建或复用 Worktree，返回绝对路径 */
    public Path create(String slug) throws IOException {
        if (!isValidSlug(slug)) {
            throw new IllegalArgumentException("非法的 slug: " + slug);
        }

        Path worktreesDir = projectRoot.resolve(WORKTREES_DIR);
        Path wtPath = worktreesDir.resolve(slug);

        // 已存在则复用
        if (Files.isDirectory(wtPath)) {
            return wtPath.toAbsolutePath().normalize();
        }

        Files.createDirectories(worktreesDir);

        // git worktree add -b <branch> <path>（同时创建分支和 Worktree）
        String branch = BRANCH_PREFIX + slug;
        ProcessBuilder pb = new ProcessBuilder(
            "git", "worktree", "add", "-b", branch, wtPath.toString());
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try {
            int exit = p.waitFor();
            if (exit != 0) {
                String err = new String(p.getInputStream().readAllBytes());
                throw new IOException("git worktree add 失败 (exit=" + exit + "): " + err);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git worktree add 被中断", e);
        }

        // 复制 easycode.yaml
        Path srcConfig = projectRoot.resolve("easycode.yaml");
        if (Files.exists(srcConfig)) {
            Files.copy(srcConfig, wtPath.resolve("easycode.yaml"),
                StandardCopyOption.REPLACE_EXISTING);
        }

        // 补 .gitignore
        Path gitignore = wtPath.resolve(".gitignore");
        String content = ".easycode/\n";
        if (Files.exists(gitignore)) {
            String existing = Files.readString(gitignore);
            if (!existing.contains(".easycode/")) {
                content = existing + "\n" + content;
            } else {
                content = existing;
            }
        }
        Files.writeString(gitignore, content);

        // 提交初始文件，避免 hasChanges 误报
        try {
            ProcessBuilder addPb = new ProcessBuilder("git", "-C", wtPath.toString(),
                "add", "easycode.yaml", ".gitignore");
            addPb.redirectErrorStream(true);
            Process addP = addPb.start();
            addP.waitFor();

            ProcessBuilder commitPb = new ProcessBuilder("git", "-C", wtPath.toString(),
                "commit", "-m", "easycode: init worktree config");
            commitPb.redirectErrorStream(true);
            Process commitP = commitPb.start();
            commitP.waitFor();
        } catch (Exception ignored) {
            // 非致命：配置提交失败不影响 Worktree 使用
        }

        return wtPath.toAbsolutePath().normalize();
    }

    /** 检测 Worktree 是否有未提交变更 */
    public boolean hasChanges(Path worktreeRoot) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "git", "-C", worktreeRoot.toString(), "status", "--porcelain");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return !output.isBlank();
        } catch (Exception e) {
            // 出错时保守处理：视为有变更
            return true;
        }
    }

    /** 清理 Worktree */
    public void remove(Path worktreeRoot) {
        // 移除 git worktree 记录
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "git", "worktree", "remove", worktreeRoot.toString(), "--force");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
        } catch (Exception e) {
            // git worktree remove 失败也继续删目录
        }

        // 删除目录
        deleteRecursive(worktreeRoot);
    }

    /** 启动时清理过期 Worktree（超过 maxAgeMs 未活动） */
    public void cleanExpired(long maxAgeMs) {
        Path worktreesDir = projectRoot.resolve(WORKTREES_DIR);
        if (!Files.isDirectory(worktreesDir)) return;

        long now = System.currentTimeMillis();
        try (Stream<Path> entries = Files.list(worktreesDir)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                try {
                    FileTime mtime = Files.getLastModifiedTime(dir);
                    long age = now - mtime.to(TimeUnit.MILLISECONDS);
                    if (age > maxAgeMs) {
                        System.err.println("[worktree] 清理过期: " + dir.getFileName());
                        remove(dir);
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    /** 获取 Worktree 根目录路径 */
    public Path worktreesDir() {
        return projectRoot.resolve(WORKTREES_DIR);
    }

    private void deleteRecursive(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }
}
