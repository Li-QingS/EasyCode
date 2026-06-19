package com.easycode.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件追踪状态（F19, F20）。记录最近读取的文件，供恢复段使用。线程安全。
 */
public final class FileTracker {
    private final LinkedHashMap<String, FileSnapshot> files = new LinkedHashMap<String, FileSnapshot>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FileSnapshot> eldest) {
            return size() > 20; // 保留最多 20 条，recentSnapshots 再截到 5
        }
    };

    /** 记录一次成功的文件读取。用文件路径去重（保留最后一次）。 */
    public synchronized void record(String path, String rawContent) {
        files.remove(path); // 先移除旧的，再插入到尾部（保持插入顺序）
        files.put(path, new FileSnapshot(path, System.currentTimeMillis(), rawContent));
    }

    /** 获取最近 N 个文件快照，按读取时间倒序。 */
    public synchronized List<FileSnapshot> recentSnapshots(int maxCount) {
        List<FileSnapshot> all = new ArrayList<>(files.values());
        // 倒序：最近读的在前面
        java.util.Collections.reverse(all);
        if (all.size() > maxCount) {
            all = all.subList(0, maxCount);
        }
        return List.copyOf(all);
    }

    /** 文件快照 */
    public record FileSnapshot(String path, long readTime, String content) {}
}
