package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.stream.Collectors;

public final class ToolRegistry {
    private static final ObjectMapper json = new ObjectMapper();
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) { tools.put(tool.name(), tool); }
    public Tool get(String name) {
        Tool t = tools.get(name);
        if (t == null) throw new IllegalArgumentException("未注册的工具: " + name);
        return t;
    }
    public int size() { return tools.size(); }

    /** 返回所有已注册工具的 JSON 定义 */
    public List<JsonNode> toToolsJson() {
        return toToolsJson(Tool.Permission.READ_WRITE);
    }

    /** 返回权限不超过 maxPermission 的工具的 JSON 定义 */
    public List<JsonNode> toToolsJson(Tool.Permission maxPermission) {
        List<JsonNode> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (t.permission().compareTo(maxPermission) > 0) continue;
            ObjectNode node = json.createObjectNode();
            node.put("name", t.name());
            node.put("description", t.description());
            node.set("input_schema", t.inputSchema());
            list.add(node);
        }
        return list;
    }

    /** 按分类返回工具名 */
    public Map<Tool.Category, List<String>> byCategory() {
        return tools.values().stream()
            .collect(Collectors.groupingBy(Tool::category,
                Collectors.mapping(Tool::name, Collectors.toList())));
    }

    /** 获取工具权限级别 */
    public Tool.Permission getPermission(String name) {
        Tool t = tools.get(name);
        if (t == null) throw new IllegalArgumentException("未注册的工具: " + name);
        return t.permission();
    }
}
