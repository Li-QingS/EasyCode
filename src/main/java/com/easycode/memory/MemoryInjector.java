package com.easycode.memory;

import java.nio.file.Path;

/** 记忆注入（F32-F34）。读取两级索引 → 截断 → 返回注入文本。 */
public final class MemoryInjector {
    private static final int MAX_INDEX_BYTES = 25_000;

    private MemoryInjector() {}

    /** 读取项目级(在前) + 用户级索引，拼接后截断到 25KB */
    public static String build(Path projectMemoryDir, Path userMemoryDir) {
        MemoryStore proj = new MemoryStore(projectMemoryDir);
        MemoryStore user = new MemoryStore(userMemoryDir);
        StringBuilder sb = new StringBuilder();
        String pi = proj.readIndex();
        if (!pi.isBlank()) sb.append("## 项目笔记\n\n").append(pi).append("\n");
        String ui = user.readIndex();
        if (!ui.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("## 用户笔记\n\n").append(ui);
        }
        String result = sb.toString().trim();
        byte[] bytes = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_INDEX_BYTES) {
            String truncated = new String(bytes, 0, MAX_INDEX_BYTES, java.nio.charset.StandardCharsets.UTF_8);
            // 避免截断在多字节字符中间
            int lastValid = truncated.length() - 1;
            while (lastValid >= 0 && Character.isSurrogate(truncated.charAt(lastValid))) lastValid--;
            result = truncated.substring(0, lastValid + 1) + "\n(index truncated)";
        }
        return result;
    }
}
