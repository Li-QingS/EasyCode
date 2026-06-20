package com.easycode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 笔记文件 CRUD + 索引管理（F27-F31）。线程安全。 */
public final class MemoryStore {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final Path memoryDir;

    public MemoryStore(Path memoryDir) {
        this.memoryDir = memoryDir;
        ensureDir();
    }

    private void ensureDir() {
        try { Files.createDirectories(memoryDir); } catch (IOException ignored) {}
    }

    /** F28/F31: 创建笔记 */
    public synchronized void create(String type, String slug, String title, String content) throws IOException {
        String now = ZonedDateTime.now().format(ISO);
        String md = "---\ntype: " + type + "\ntitle: " + title + "\ncreated: " + now + "\nupdated: " + now + "\n---\n\n" + content + "\n";
        Path file = memoryDir.resolve(type + "_" + slug + ".md");
        Path tmp = memoryDir.resolve("." + file.getFileName() + ".tmp");
        Files.writeString(tmp, md, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        appendIndex("- [" + type + "] " + title + " — " + truncateDesc(content));
    }

    /** 更新笔记 */
    public synchronized void update(String filename, String title, String content) throws IOException {
        Path file = memoryDir.resolve(filename);
        if (!Files.isRegularFile(file)) return;
        String old = Files.readString(file, StandardCharsets.UTF_8);
        String now = ZonedDateTime.now().format(ISO);
        // 更新 frontmatter 中的 updated 字段
        String updated = old.replaceFirst("updated: .*", "updated: " + now);
        // 更新 title（如果改了）
        if (title != null && !title.isBlank()) {
            updated = updated.replaceFirst("title: .*", "title: " + title);
        }
        // 更新 body（frontmatter 之后的内容）
        if (content != null && !content.isBlank()) {
            int bodyStart = updated.indexOf("---\n", 4);
            if (bodyStart >= 0) {
                updated = updated.substring(0, bodyStart + 4) + "\n\n" + content + "\n";
            }
        }
        Path tmp = memoryDir.resolve("." + filename + ".tmp");
        Files.writeString(tmp, updated, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        updateIndexLine(filename, title, content);
    }

    /** 删除笔记 */
    public synchronized void delete(String filename) throws IOException {
        Path file = memoryDir.resolve(filename);
        Files.deleteIfExists(file);
        removeIndexLine(filename);
    }

    /** 读取索引（F30） */
    public synchronized String readIndex() {
        Path idx = memoryDir.resolve("MEMORY.md");
        if (!Files.isRegularFile(idx)) return "";
        try {
            return Files.readString(idx, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    // ---- 索引操作 ----

    private Path indexFile() { return memoryDir.resolve("MEMORY.md"); }

    private void appendIndex(String line) throws IOException {
        Path idx = indexFile();
        String existing = Files.isRegularFile(idx) ? Files.readString(idx, StandardCharsets.UTF_8) : "";
        Files.writeString(idx, (existing.isEmpty() ? "" : existing + "\n") + line, StandardCharsets.UTF_8);
    }

    private void updateIndexLine(String filename, String title, String content) throws IOException {
        Path idx = indexFile();
        if (!Files.isRegularFile(idx)) return;
        List<String> lines = new ArrayList<>(Files.readAllLines(idx, StandardCharsets.UTF_8));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(filename)) {
                String newLine = "- [";
                // 从原行提取 type
                int t1 = lines.get(i).indexOf('[');
                int t2 = lines.get(i).indexOf(']', t1);
                String type = t1 >= 0 && t2 > t1 ? lines.get(i).substring(t1 + 1, t2) : "?";
                newLine += type + "] " + (title != null ? title : "") + " — " + truncateDesc(content);
                lines.set(i, newLine);
                break;
            }
        }
        Files.write(idx, lines, StandardCharsets.UTF_8);
    }

    private void removeIndexLine(String filename) throws IOException {
        Path idx = indexFile();
        if (!Files.isRegularFile(idx)) return;
        List<String> lines = new ArrayList<>(Files.readAllLines(idx, StandardCharsets.UTF_8));
        lines.removeIf(l -> l.contains(filename));
        Files.write(idx, lines, StandardCharsets.UTF_8);
    }

    private static String truncateDesc(String content) {
        if (content == null || content.isBlank()) return "";
        String s = content.replace('\n', ' ').trim();
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
