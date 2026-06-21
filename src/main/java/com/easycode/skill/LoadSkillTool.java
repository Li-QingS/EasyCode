package com.easycode.skill;

import com.easycode.tool.Tool;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 系统级工具：Agent 调用后加载并激活 Skill，白名单永不限制它 */
public final class LoadSkillTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SkillRegistry registry;

    public LoadSkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "load_skill"; }

    @Override public String description() {
        return "加载并激活一个 Skill。参数 name 为 Skill 的唯一名称。加载后 Skill 的完整指令会出现在系统提示中，"
            + "并可能限制可见工具集。使用前请先通过可用 Skill 列表确认 name。";
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode nameProp = props.putObject("name");
        nameProp.put("type", "string");
        nameProp.put("description", "Skill 的唯一名称");
        ArrayNode required = schema.putArray("required");
        required.add("name");
        return schema;
    }

    @Override public ToolResult execute(JsonNode input) {
        try {
            JsonNode nameNode = input.get("name");
            if (nameNode == null || nameNode.isNull()) return ToolResult.err("load_skill", "缺少 name 参数", 0);
            String name = nameNode.asText();
            if (name.isBlank()) return ToolResult.err("load_skill", "name 参数不能为空", 0);
            SkillDef def = registry.load(name);
            registry.activate(name);
            String msg = "Skill '" + name + "' 已加载并激活。\n描述: " + def.frontmatter().description()
                + "\n执行模式: " + def.frontmatter().mode();
            if (def.isDirectorySkill())
                msg += "\n专属工具: " + String.join(", ", def.tools().keySet());
            return ToolResult.ok("load_skill", msg, 0);
        } catch (Exception e) {
            return ToolResult.err("load_skill", e.getMessage(), 0);
        }
    }

    @Override public Category category() { return Category.SEARCH; }
    @Override public Permission permission() { return Permission.READ_ONLY; }
}
