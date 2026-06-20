package com.easycode.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;

/** 项目指令加载器（F1, F7, F8）。三层路径顺序加载并拼接。 */
public final class InstructionLoader {
    private static volatile String cached;

    private InstructionLoader() {}

    /** 按三层顺序加载并拼接（F1）。结果缓存（F8）。 */
    public static String load(Path projectRoot) {
        if (cached != null) return cached;
        StringBuilder sb = new StringBuilder();
        Path home = Path.of(System.getProperty("user.home", ""));

        // ① 项目根 EasyCode.md（最高优先级）
        Path easyCode = projectRoot.resolve("EasyCode.md");
        Path codex = projectRoot.resolve("CODEX.md");
        appendIfExists(sb, Files.isRegularFile(easyCode) ? easyCode : codex, projectRoot, projectRoot);
        // ② .easycode/EasyCode.md
        appendIfExists(sb, projectRoot.resolve(".easycode/EasyCode.md"), projectRoot, projectRoot);
        // ③ ~/.easycode/EasyCode.md（最低优先级）
        appendIfExists(sb, home.resolve(".easycode/EasyCode.md"), home.resolve(".easycode"), home.resolve(".easycode"));

        cached = sb.toString().trim();
        // 段落级去重：按 \n\n 拆段，只保留首次出现的段落（高层优先保留，低层重复丢弃）
        if (!cached.isEmpty()) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String para : cached.split("\n\n")) {
                String trimmed = para.trim();
                if (!trimmed.isBlank()) seen.add(trimmed);
            }
            cached = String.join("\n\n", seen);
        }
        return cached;
    }

    private static void appendIfExists(StringBuilder sb, Path file, Path baseDir, Path rootBoundary) {
        if (!Files.isRegularFile(file)) return;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String expanded = IncludeResolver.resolve(raw, baseDir, rootBoundary, 1, new HashSet<>());
            if (!expanded.isBlank()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(expanded);
            }
        } catch (IOException e) {
            // 静默跳过（F6）
        }
    }

    /** 清除缓存（测试用） */
    static void clearCache() { cached = null; }
}
