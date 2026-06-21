 package com.easycode.skill;

 import com.easycode.tool.ToolRegistry;
 import com.fasterxml.jackson.databind.JsonNode;
 import com.fasterxml.jackson.databind.ObjectMapper;
 import java.io.BufferedReader;
 import java.io.InputStream;
 import java.io.InputStreamReader;
 import java.nio.charset.StandardCharsets;
 import java.nio.file.Files;
 import java.nio.file.Path;
 import java.util.ArrayList;
 import java.util.Collections;
 import java.util.LinkedHashMap;
 import java.util.List;
 import java.util.Map;
 import java.util.stream.Collectors;
 import java.util.stream.Stream;

 /** 三级目录扫描、YAML frontmatter 解析、优先级覆盖、启动校验 */
 public final class SkillLoader {
     private static final ObjectMapper JSON = new ObjectMapper();
     private static final List<String> BUILTIN_NAMES = List.of("commit", "review", "test");

     private final ToolRegistry toolRegistry;
     private final Path projectRoot;

     public SkillLoader(ToolRegistry toolRegistry, Path projectRoot) {
         this.toolRegistry = toolRegistry;
         this.projectRoot = projectRoot;
     }

     /** 三级扫描并去重（项目 > 用户 > 内置） */
     public List<SkillFrontmatter> loadAll() {
         LinkedHashMap<String, SkillFrontmatter> map = new LinkedHashMap<>();
         // 顺序：内置 → 用户 → 项目（后加载覆盖先加载）
         scanBuiltin(map);
         scanDir(Path.of(System.getProperty("user.home"), ".easycode/skills"), map);
         scanDir(projectRoot.resolve(".easycode/skills"), map);
         return new ArrayList<>(map.values());
     }

     /** 校验所有 frontmatter 的 allowedTools 在 ToolRegistry 中存在 */
     public void validateAllowedTools(List<SkillFrontmatter> frontmatters) {
         for (SkillFrontmatter fm : frontmatters) {
             for (String toolName : fm.allowedTools()) {
                 if (toolRegistry.getQuiet(toolName) == null) {
                     throw new IllegalStateException(
                         "Skill '" + fm.name() + "' 的 allowedTools 中包含不存在的工具: " + toolName);
                 }
             }
         }
     }

    // ====== 内置扫描 ======

    private void scanBuiltin(LinkedHashMap<String, SkillFrontmatter> map) {
        for (String name : BUILTIN_NAMES) {
            String resourcePath = "/skills/" + name + ".md";
            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                if (is == null) continue;
                String content = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
                SkillFrontmatter fm = parseFrontmatter(content, Path.of("builtin:" + resourcePath));
                map.put(fm.name(), fm);
            } catch (Exception e) {
                System.err.println("[SkillLoader] 内置 Skill 加载失败: " + resourcePath + " — " + e.getMessage());
            }
        }
    }

    // ====== 目录扫描 ======

    private void scanDir(Path dir, LinkedHashMap<String, SkillFrontmatter> map) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                try {
                    if (Files.isDirectory(entry)) {
                        scanDirectorySkill(entry, map);
                    } else if (entry.getFileName().toString().endsWith(".md")) {
                        scanSingleFileSkill(entry, map);
                    }
                } catch (Exception e) {
                    System.err.println("[SkillLoader] 跳过: " + entry + " — " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[SkillLoader] 目录扫描失败: " + dir + " — " + e.getMessage());
        }
    }

    private void scanSingleFileSkill(Path file, LinkedHashMap<String, SkillFrontmatter> map) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            SkillFrontmatter fm = parseFrontmatter(content, file);
            map.put(fm.name(), fm);
        } catch (Exception e) {
            System.err.println("[SkillLoader] 跳过 (frontmatter 解析失败): " + file + " — " + e.getMessage());
        }
    }

    private void scanDirectorySkill(Path dir, LinkedHashMap<String, SkillFrontmatter> map) {
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) return;
        try {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            SkillFrontmatter fm = parseFrontmatter(content, skillMd);
            map.put(fm.name(), fm);
        } catch (Exception e) {
            System.err.println("[SkillLoader] 跳过 (目录 Skill 解析失败): " + skillMd + " — " + e.getMessage());
        }
    }

    // ====== 完整加载 ======

    /** 加载完整 Skill 定义（promptBody + tool.json） */
    public SkillDef loadFull(SkillFrontmatter fm) {
        try {
            String raw;
            Path src = fm.sourcePath();
            String srcStr = src.toString();
            if (srcStr.contains("builtin:")) {
                int bi = srcStr.indexOf("builtin:");
                String resPath = srcStr.substring(bi + "builtin:".length());
                try (InputStream is = getClass().getResourceAsStream(resPath)) {
                    if (is == null) throw new RuntimeException("内置资源不存在: " + resPath);
                    raw = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
                }
            } else {
                raw = Files.readString(src, StandardCharsets.UTF_8);
            }
            String body = extractBody(raw);
            Map<String, SkillDef.ToolDefinition> tools = Collections.emptyMap();
            if (!srcStr.startsWith("builtin:")) {
                Path parent = src.getParent();
                if (parent != null) {
                    Path toolJson = parent.resolve("tool.json");
                    if (Files.isRegularFile(toolJson)) {
                        tools = parseToolJson(toolJson, parent);
                    }
                }
            }
            return new SkillDef(fm, body, tools);
        } catch (Exception e) {
            throw new RuntimeException("加载 Skill 失败: " + fm.name(), e);
        }
    }
    // ====== 辅助方法 ======

    /** 解析 frontmatter 并返回 SkillFrontmatter */
    static SkillFrontmatter parseFrontmatter(String content, Path source) {
        String[] parts = content.split("---", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("缺少 YAML frontmatter (--- 分隔符)");
        }
        String yamlBlock = parts[1].trim();
        if (yamlBlock.isEmpty()) {
            throw new IllegalArgumentException("frontmatter 内容为空");
        }
        return SkillFrontmatter.fromYaml(yamlBlock, source.toAbsolutePath().normalize());
    }

    /** 提取 Markdown 正文（去除 frontmatter） */
    static String extractBody(String content) {
        String[] parts = content.split("---", 3);
        if (parts.length >= 3) {
            return parts[2].trim();
        }
        return ""; // 没有正文
    }

    /** 解析 tool.json */
    @SuppressWarnings("unchecked")
    private Map<String, SkillDef.ToolDefinition> parseToolJson(Path toolJson, Path skillDir) {
        try {
            Map<String, Object> root = JSON.readValue(toolJson.toFile(), Map.class);
            Object toolsObj = root.get("tools");
            if (!(toolsObj instanceof List<?> list)) return Collections.emptyMap();

            Map<String, SkillDef.ToolDefinition> result = new LinkedHashMap<>();
            for (Object item : list) {
                if (!(item instanceof Map<?,?> m)) continue;
                Map<String, Object> tm = (Map<String, Object>) m;
                String name = (String) tm.get("name");
                String desc = (String) tm.getOrDefault("description", "");
                JsonNode schema = JSON.valueToTree(tm.getOrDefault("inputSchema", Map.of()));
                String script = (String) tm.get("script");
                Path scriptPath = script != null ? skillDir.resolve(script) : null;
                result.put(name, new SkillDef.ToolDefinition(name, desc, schema, scriptPath));
            }
            return result;
        } catch (Exception e) {
            System.err.println("[SkillLoader] tool.json 解析失败: " + toolJson + " — " + e.getMessage());
            return Collections.emptyMap();
        }
    }
 }
