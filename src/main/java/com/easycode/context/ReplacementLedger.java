package com.easycode.context;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 替换决策账本（F5）。线程安全——所有写操作在 ReentrantLock 临界区内原子完成。
 * 一旦决策写入，本会话内不再翻转。
 */
public final class ReplacementLedger {
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> seenIds = new HashSet<>();
    private final Map<String, String> replacements = new HashMap<>();

    /**
     * 原子决策：给定 toolUseId 和原始 content，返回该用的字符串（原文或预览）。
     * decisionFn 返回 null 表示"不替换"（保留原文），此时只写 seenIds，不写 replacements。
     */
    public String decide(String toolUseId, String rawContent, Function<String, String> decisionFn) {
        lock.lock();
        try {
            if (seenIds.contains(toolUseId)) {
                return replacements.getOrDefault(toolUseId, rawContent);
            }
            String result = decisionFn.apply(rawContent);
            seenIds.add(toolUseId);
            if (result != null) {
                replacements.put(toolUseId, result);
                return result;
            }
            return rawContent;
        } finally {
            lock.unlock();
        }
    }

    /** 查询已决策的替换文本（不修改状态）。null 表示未替换或未决策。 */
    public String getReplacement(String toolUseId) {
        lock.lock();
        try {
            return replacements.get(toolUseId);
        } finally {
            lock.unlock();
        }
    }

    /** 该 id 是否已被决策过（无论替换与否）。 */
    public boolean isSeen(String toolUseId) {
        lock.lock();
        try {
            return seenIds.contains(toolUseId);
        } finally {
            lock.unlock();
        }
    }

    /** 返回不可变快照（测试用） */
    public Map<String, String> snapshot() {
        lock.lock();
        try {
            return Map.copyOf(replacements);
        } finally {
            lock.unlock();
        }
    }
}
