 package com.easycode.skill;

 import com.fasterxml.jackson.databind.ObjectMapper;
 import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
 import java.nio.file.Path;
 import java.util.Collections;
 import java.util.List;
 import java.util.Map;

 /** 从 YAML frontmatter 解析的轻量元信息 */
 public record SkillFrontmatter(
     String name,
     String description,
     String mode,
     String context,
     String model,
     List<String> allowedTools,
     Path sourcePath
 ) {
     private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

     /** 紧凑构造器校验：name 不能为空，可选字段给默认值 */
     public SkillFrontmatter {
         if (name == null || name.isBlank())
             throw new IllegalArgumentException("Skill name 不能为空");
         if (description == null || description.isBlank())
             description = "(无描述)";
         if (mode == null || mode.isBlank())
             mode = "inline";
         if (context == null || context.isBlank())
             context = "none";
         if (allowedTools == null)
             allowedTools = List.of();
     }

     /** 从 YAML 字符串解析并构建 */
     @SuppressWarnings("unchecked")
     public static SkillFrontmatter fromYaml(String yamlBlock, Path source) {
         try {
             Map<String, Object> map = YAML.readValue(yamlBlock, Map.class);
             String name = str(map, "name");
             String desc = str(map, "description");
             String mode = str(map, "mode");
             String context = str(map, "context");
             String model = str(map, "model");
             List<String> allowed = list(map, "allowedTools");
             return new SkillFrontmatter(name, desc, mode, context, model, allowed, source);
         } catch (Exception e) {
             throw new IllegalArgumentException("Skill frontmatter 解析失败: " + source, e);
         }
     }

     private static String str(Map<String, Object> map, String key) {
         Object v = map.get(key);
         return v != null ? v.toString() : null;
     }

     @SuppressWarnings("unchecked")
     private static List<String> list(Map<String, Object> map, String key) {
         Object v = map.get(key);
         if (v instanceof List<?> l) return (List<String>) l;
         return Collections.emptyList();
     }
 }
