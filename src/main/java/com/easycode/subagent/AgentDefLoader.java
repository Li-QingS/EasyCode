package com.easycode.subagent;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** 三来源加载 Agent 定义（builtin → user → project，同名覆盖） */
public final class AgentDefLoader {

    private AgentDefLoader() {}

    @SuppressWarnings("unchecked")
    public static Map<String, AgentDef> loadAll(Path projectDir) {
        Map<String, AgentDef> all = new LinkedHashMap<>();

        // Tier 1: builtin（从 classpath 加载 resources/builtin/agents/*.md）
        loadBuiltin(all);

        // Tier 2: user（~/.easycode/agents/）
        String home = System.getProperty("user.home");
        if (home != null) {
            loadFromDir(Path.of(home, ".easycode", "agents"), all);
        }

        // Tier 3: project（.easycode/agents/）
        loadFromDir(projectDir.resolve(".easycode/agents"), all);

        return all;
    }

    private static void loadBuiltin(Map<String, AgentDef> all) {
        // Scan classpath for builtin/agents/*.md
        try {
            var classLoader = AgentDefLoader.class.getClassLoader();
            var dirUrl = classLoader.getResource("builtin/agents");
            if (dirUrl == null) return;
            if ("jar".equals(dirUrl.getProtocol())) {
                // JAR resource: scan via FileSystem
                var fs = FileSystems.newFileSystem(dirUrl.toURI(), Map.of());
                try (Stream<Path> files = Files.list(fs.getPath("builtin/agents"))) {
                    files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                        try {
                            AgentDef def = parse(Files.readString(p), p.getFileName().toString());
                            all.put(def.name(), def);
                        } catch (IOException ignored) {}
                    });
                }
                fs.close();
            } else {
                // File system directory (IDE/dev mode)
                try (Stream<Path> files = Files.list(Path.of(dirUrl.toURI()))) {
                    files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                        try {
                            AgentDef def = parse(Files.readString(p), p.getFileName().toString());
                            all.put(def.name(), def);
                        } catch (IOException ignored) {}
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("[agent-loader] builtin load failed: " + e.getMessage());
        }
    }

    private static void loadFromDir(Path dir, Map<String, AgentDef> all) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                try {
                    AgentDef def = parse(Files.readString(p), p.getFileName().toString());
                    all.put(def.name(), def); // 同名覆盖
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    @SuppressWarnings("unchecked")
    static AgentDef parse(String content, String fileName) {
        String body = content;
        Map<String, Object> frontMatter = Map.of();

        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int firstSep = content.indexOf("---");
            int secondSep = content.indexOf("---", firstSep + 3);
            if (secondSep >= 0) {
                String yamlBlock = content.substring(firstSep + 3, secondSep);
                body = content.substring(secondSep + 3).strip();
                try {
                    Map<String, Object> parsed = new Yaml().load(yamlBlock);
                    if (parsed != null) frontMatter = parsed;
                } catch (Exception ignored) {}
            }
        }

        String name = stringVal(frontMatter, "name");
        if (name == null || name.isBlank()) {
            name = fileName.replace(".md", "").toLowerCase().replace(' ', '-');
        }
        String desc = stringVal(frontMatter, "description");
        List<String> toolsAllow = listVal(frontMatter, "tools_allow");
        List<String> toolsDeny = listVal(frontMatter, "tools_deny");
        String model = stringVal(frontMatter, "model");
        int maxTurns = intVal(frontMatter, "max_turns", 10);
        String permission = stringVal(frontMatter, "permission");
        String isolation = stringVal(frontMatter, "isolation");
        int timeoutSec = intVal(frontMatter, "timeout_sec", 120);

        return new AgentDef(name != null ? name : "", desc != null ? desc : "",
            body, toolsAllow, toolsDeny, model != null ? model : "", maxTurns,
            permission != null ? permission : "",
            isolation != null ? isolation : "none", timeoutSec);

    }

    private static String stringVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List) return (List<String>) v;
        return List.of();
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) {} }
        return def;
    }
}
