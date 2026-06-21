 package com.easycode.skill;

 import com.easycode.tool.Tool;
import com.easycode.tool.ToolResult;
 import com.easycode.tool.ToolRegistry;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
 import com.fasterxml.jackson.databind.JsonNode;
 import java.nio.file.Path;
 import java.util.Collections;
 import java.util.LinkedHashMap;
 import java.util.LinkedHashSet;
 import java.util.List;
 import java.util.Map;
 import java.util.Set;

 /** 运行时两阶段管理：轻量列表 → 按需加载 → 激活/停用 → 白名单控制 */
 public final class SkillRegistry {
     private final Map<String, SkillFrontmatter> lightweight = new LinkedHashMap<>();
     private final Map<String, SkillDef> loaded = new LinkedHashMap<>();
     private final Set<String> activated = new LinkedHashSet<>();
     private final Map<String, Set<String>> activatedTools = new LinkedHashMap<>();
     private final ToolRegistry toolRegistry;
     private final SkillLoader skillLoader;
     private boolean whitelistActive; // true 表示白名单生效中

     public SkillRegistry(ToolRegistry toolRegistry, SkillLoader skillLoader) {
         this.toolRegistry = toolRegistry;
         this.skillLoader = skillLoader;
     }

     // ====== 轻量层 ======

     public synchronized void initialize(List<SkillFrontmatter> frontmatters) {
         lightweight.clear();
         for (SkillFrontmatter fm : frontmatters) {
             lightweight.put(fm.name(), fm);
         }
     }

     public synchronized List<SkillFrontmatter> listAll() {
         return List.copyOf(lightweight.values());
     }

     public synchronized String buildAvailableSkillsText() {
         if (lightweight.isEmpty()) return "";
         StringBuilder sb = new StringBuilder();
         sb.append("可用 Skill:\n");
         for (SkillFrontmatter fm : lightweight.values()) {
             sb.append("  /").append(fm.name()).append(" — ").append(fm.description()).append('\n');
         }
         return sb.toString();
     }

     // ====== 加载 ======

     public synchronized SkillDef load(String name) {
         SkillDef cached = loaded.get(name);
         if (cached != null) return cached;

         SkillFrontmatter fm = lightweight.get(name);
         if (fm == null) throw new IllegalArgumentException("Skill 不存在: " + name);

         SkillDef def = skillLoader.loadFull(fm);
         loaded.put(name, def);
         return def;
     }

     // ====== 激活/停用 ======

     public synchronized void activate(String name) {
         SkillDef def = load(name);
         activated.add(name);
         whitelistActive = !computeToolWhitelist().isEmpty();
         // 目录型 Skill：注册专属工具
         Set<String> registered = new LinkedHashSet<>();
         for (SkillDef.ToolDefinition td : def.tools().values()) {
             if (toolRegistry.getQuiet(td.name()) == null) {
                 toolRegistry.register(new SkillProvidedTool(td, toolRegistry));
                 registered.add(td.name());
             }
         }
         if (!registered.isEmpty()) activatedTools.put(name, registered);
     }

     public synchronized void deactivate(String name) {
         activated.remove(name);
         // 注销专属工具
         Set<String> toolNames = activatedTools.remove(name);
         if (toolNames != null) {
             for (String tn : toolNames) toolRegistry.remove(tn);
         }
         whitelistActive = !computeToolWhitelist().isEmpty();
     }

     public synchronized void clearActivated() {
         for (Set<String> toolNames : activatedTools.values()) {
             for (String tn : toolNames) toolRegistry.remove(tn);
         }
         activatedTools.clear();
         activated.clear();
         whitelistActive = false;
     }

     /** 清空白名单但保持 Skill 激活（收到 SKILL_END 时调用） */
     public synchronized void clearWhitelist() {
         whitelistActive = false;
     }

     /** 返回已激活 Skill 的 SOP 正文拼接 */
     public synchronized String getActivatedPrompt() {
         if (activated.isEmpty()) return "";
         StringBuilder sb = new StringBuilder();
         sb.append("## 已激活 Skill\n\n");
         for (String name : activated) {
             SkillDef def = loaded.get(name);
             if (def == null) continue;
             sb.append("### Skill: ").append(name).append("\n");
             sb.append(def.frontmatter().description()).append("\n\n");
             sb.append(def.promptBody()).append("\n\n");
         }
         return sb.toString().trim();
     }

     // ====== 白名单 ======

     /** 获取当前所有 activated Skill 的 allowedTools 并集。白名单未激活时返回空集。 */
     public synchronized Set<String> activeToolWhitelist() {
         if (!whitelistActive) return Collections.emptySet();
         return computeToolWhitelist();
     }

     /** 直接计算白名单并集，不检查 whitelistActive 标志（供 activate/deactivate 内部使用） */
     private Set<String> computeToolWhitelist() {
         Set<String> result = new LinkedHashSet<>();
         for (String name : activated) {
             SkillDef def = loaded.get(name);
             if (def == null) {
                 SkillFrontmatter fm = lightweight.get(name);
                 if (fm != null) result.addAll(fm.allowedTools());
             } else {
                 result.addAll(def.frontmatter().allowedTools());
             }
         }
         return result;
     }

     public synchronized boolean hasActivated() {
         return !activated.isEmpty();
     }

     // ====== 热更新 ======

     public synchronized List<String> reload(List<SkillFrontmatter> fresh) {
         initialize(fresh);
         // 刷新已加载 Skill 的缓存（如果有变更）
         loaded.keySet().removeIf(name -> !lightweight.containsKey(name));
         return List.copyOf(lightweight.keySet());
     }

     // ====== Skill 专属工具适配器 ======

     private static class SkillProvidedTool implements Tool {
         private static final ObjectMapper SKILL_JSON = new ObjectMapper();
         private final SkillDef.ToolDefinition def;
         private final ToolRegistry toolRegistry;

         SkillProvidedTool(SkillDef.ToolDefinition def, ToolRegistry toolRegistry) {
             this.def = def; this.toolRegistry = toolRegistry;
         }
         @Override public String name() { return def.name(); }
         @Override public String description() { return def.description(); }
         @Override public JsonNode inputSchema() { return def.inputSchema(); }
         @Override public ToolResult execute(JsonNode input) {
             // 委托给 exec_command 工具，走权限管线
             if (def.scriptPath() == null) {
                 return ToolResult.err(def.name(), "缺少实现脚本路径", 0);
             }
             try {
                 String jsonInput = SKILL_JSON.writeValueAsString(input);
                 String cmd = shellEscape(def.scriptPath().toString()) + " " + shellEscape(jsonInput);
                 ObjectNode cmdInput = SKILL_JSON.createObjectNode();
                 cmdInput.put("command", cmd);
                 Tool execTool = toolRegistry.get("exec_command");
                 return execTool != null ? execTool.execute(cmdInput)
                     : ToolResult.err(def.name(), "exec_command 工具不可用", 0);
             } catch (Exception e) {
                 return ToolResult.err(def.name(), e.getMessage(), 0);
             }
         }
         @Override public Category category() { return Category.SHELL; }
         @Override public Permission permission() { return Permission.READ_WRITE; }
         @Override public boolean isDestructive() { return true; }

         private static String shellEscape(String s) {
             return "'" + s.replace("'", "'\''") + "'";
         }
     }
 }
