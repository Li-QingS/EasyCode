package com.easycode.team;

import com.easycode.tool.Tool;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.stream.Collectors;

/** 共享任务工具：仅小组成员可见，支持增删查改 */
public class TaskListTool implements Tool {

    private static final ObjectMapper json = new ObjectMapper();
    private final SharedTaskList taskList;

    public TaskListTool(SharedTaskList taskList) {
        this.taskList = taskList;
    }

    @Override public String name() { return "team_task"; }

    @Override public String description() {
        return """
            小组共享任务操作。支持以下子命令：
            - add: 添加任务，参数 title(string,必填)、description(string)、dependsOn(string数组,可选)、assignTo(string,可选)
            - list: 列出任务，参数 status(TODO|IN_PROGRESS|DONE|BLOCKED,可选)、assignedTo(string,可选)
            - get: 查看单个任务详情，参数 id(string,必填)
            - update: 更新任务，参数 id(string,必填)、status(string,可选)、assignedTo(string,可选)、description(string,可选)
            - ready: 列出依赖已满足可以开工的 TODO 任务
            注意：add 时 dependsOn 中的任务 ID 必须已存在。""";
    }

    @Override public Category category() { return Category.SHELL; }

    @Override public Permission permission() { return Permission.READ_WRITE; }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("action").put("type", "string")
            .put("description", "操作: add|list|get|update|ready");
        props.putObject("id").put("type", "string");
        props.putObject("title").put("type", "string");
        props.putObject("description").put("type", "string");
        props.putObject("status").put("type", "string");
        props.putObject("assignedTo").put("type", "string");
        ArrayNode deps = props.putArray("dependsOn");
        deps.addObject().put("type", "string");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode input) {
        String action = input.has("action") ? input.get("action").asText() : "list";
        try {
            return switch (action) {
                case "add" -> doAdd(input);
                case "list" -> doList(input);
                case "get" -> doGet(input);
                case "update" -> doUpdate(input);
                case "ready" -> doReady();
                default -> ToolResult.err("team_task", "未知操作: " + action, 0);
            };
        } catch (Exception e) {
            return ToolResult.err("team_task", e.getMessage(), 0);
        }
    }

    private ToolResult doAdd(JsonNode input) {
        String title = input.has("title") ? input.get("title").asText() : "";
        if (title.isBlank()) return ToolResult.err("team_task", "title 不能为空", 0);
        String desc = input.has("description") ? input.get("description").asText() : "";
        List<String> deps = new ArrayList<>();
        if (input.has("dependsOn") && input.get("dependsOn").isArray()) {
            for (JsonNode d : input.get("dependsOn")) deps.add(d.asText());
        }
        String assignTo = input.has("assignTo") ? input.get("assignTo").asText() : null;
        SharedTask task = taskList.addTask(title, desc, deps, assignTo);
        return ToolResult.ok("team_task", formatTask(task), task.id().length());
    }

    private ToolResult doList(JsonNode input) {
        TaskStatus filterStatus = null;
        if (input.has("status") && !input.get("status").asText().isBlank()) {
            filterStatus = TaskStatus.valueOf(input.get("status").asText().toUpperCase());
        }
        String filterAssigned = input.has("assignedTo") ? input.get("assignedTo").asText() : null;
        if (filterAssigned != null && filterAssigned.isBlank()) filterAssigned = null;

        List<SharedTask> tasks = taskList.listTasks(filterStatus, filterAssigned);
        StringBuilder sb = new StringBuilder("共 " + tasks.size() + " 个任务:\n");
        for (SharedTask t : tasks) {
            sb.append(formatTask(t)).append("\n");
        }
        return ToolResult.ok("team_task", sb.toString().trim(), sb.length());
    }

    private ToolResult doGet(JsonNode input) {
        String id = input.has("id") ? input.get("id").asText() : "";
        if (id.isBlank()) return ToolResult.err("team_task", "id 不能为空", 0);
        return ToolResult.ok("team_task", formatTask(taskList.getTask(id)), id.length());
    }

    private ToolResult doUpdate(JsonNode input) {
        String id = input.has("id") ? input.get("id").asText() : "";
        if (id.isBlank()) return ToolResult.err("team_task", "id 不能为空", 0);
        TaskStatus newStatus = input.has("status") && !input.get("status").asText().isBlank()
            ? TaskStatus.valueOf(input.get("status").asText().toUpperCase()) : null;
        String newAssign = input.has("assignedTo") ? input.get("assignedTo").asText() : null;
        String newDesc = input.has("description") ? input.get("description").asText() : null;
        SharedTask updated = taskList.updateTask(id, newStatus, newAssign, newDesc);
        return ToolResult.ok("team_task", formatTask(updated), updated.id().length());
    }

    private ToolResult doReady() {
        List<SharedTask> ready = taskList.readyTasks();
        StringBuilder sb = new StringBuilder("就绪任务 (" + ready.size() + "):\n");
        for (SharedTask t : ready) sb.append(formatTask(t)).append("\n");
        return ToolResult.ok("team_task", sb.toString().trim(), sb.length());
    }

    private String formatTask(SharedTask t) {
        return String.format("[%s] %s | 状态:%s | 指派:%s | 依赖:%s",
            t.id(), t.title(), t.status(),
            t.assignedTo() != null ? t.assignedTo() : "未分配",
            t.dependsOn().isEmpty() ? "无" : String.join(",", t.dependsOn()));
    }
}
