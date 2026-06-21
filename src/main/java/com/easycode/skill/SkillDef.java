 package com.easycode.skill;

 import com.fasterxml.jackson.databind.JsonNode;
 import java.nio.file.Path;
 import java.util.Collections;
 import java.util.Map;

 /** 完整 Skill 定义 = frontmatter + 正文 + 可选专属工具 */
 public record SkillDef(
     SkillFrontmatter frontmatter,
     String promptBody,
     Map<String, ToolDefinition> tools
 ) {
     /** 目录型 Skill 的专属工具定义 */
     public record ToolDefinition(
         String name,
         String description,
         JsonNode inputSchema,
         Path scriptPath
     ) {}

     /** 紧凑构造器校验 */
     public SkillDef {
         if (promptBody == null) promptBody = "";
         if (tools == null) tools = Collections.emptyMap();
     }

     /** 是否为目录型 Skill（含专属工具） */
     public boolean isDirectorySkill() {
         return !tools.isEmpty();
     }
 }
