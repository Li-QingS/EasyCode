package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具注册中心：登记工具、按名查找、生成 API 格式 tools 数组 */
public final class ToolRegistry {

    private static final ObjectMapper json = new ObjectMapper();
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        Tool t = tools.get(name);
        if (t == null) throw new IllegalArgumentException("未注册的工具: " + name);
        return t;
    }

    public int size() {
        return tools.size();
    }

    /** 生成 Anthropic 兼容的 tools 数组 JSON */
    public List<JsonNode> toToolsJson() {
        List<JsonNode> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            ObjectNode node = json.createObjectNode();
            node.put("name", t.name());
            node.put("description", t.description());
            node.set("input_schema", t.inputSchema());
            list.add(node);
        }
        return list;
    }
}
