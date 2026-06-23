package com.easycode.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 共享任务列表：内存 Map + JSONL 文件持久化 */
public class SharedTaskList {

    private static final ObjectMapper json = new ObjectMapper();

    private final Path tasksFile;
    private final Map<String, SharedTask> tasks = new ConcurrentHashMap<>();

    public SharedTaskList(Path tasksFile) {
        this.tasksFile = tasksFile;
        load();
    }

    /** 从文件加载所有任务 */
    private void load() {
        if (!Files.exists(tasksFile)) return;
        try {
            for (String line : Files.readAllLines(tasksFile)) {
                if (line.isBlank()) continue;
                try {
                    SharedTask task = parseTask(line);
                    tasks.put(task.id(), task);
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    /** 全量写回文件 */
    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (SharedTask t : tasks.values()) {
            sb.append(toJson(t)).append("\n");
        }
        Files.writeString(tasksFile, sb.toString());
    }

    /** 添加任务 */
    public SharedTask addTask(String title, String description, List<String> dependsOn, String assignedTo) {
        // 依赖校验
        if (dependsOn != null) {
            for (String depId : dependsOn) {
                if (!tasks.containsKey(depId)) {
                    throw new IllegalArgumentException("依赖的任务不存在: " + depId);
                }
            }
        }
        SharedTask task = new SharedTask(title, description,
            dependsOn != null ? dependsOn : List.of(), assignedTo);
        tasks.put(task.id(), task);
        try { save(); } catch (IOException e) {
            throw new RuntimeException("保存任务失败: " + e.getMessage(), e);
        }
        return task;
    }

    /** 列出任务（可选过滤） */
    public List<SharedTask> listTasks(TaskStatus filterStatus, String filterAssigned) {
        return tasks.values().stream()
            .filter(t -> filterStatus == null || t.status() == filterStatus)
            .filter(t -> filterAssigned == null || filterAssigned.equals(t.assignedTo()))
            .sorted(Comparator.comparingLong(SharedTask::createdAt))
            .toList();
    }

    /** 获取单个任务 */
    public SharedTask getTask(String id) {
        SharedTask t = tasks.get(id);
        if (t == null) throw new IllegalArgumentException("任务不存在: " + id);
        return t;
    }

    /** 更新任务状态 */
    public SharedTask updateTask(String id, TaskStatus newStatus, String newAssignedTo, String newDesc) {
        SharedTask current = getTask(id);
        SharedTask updated = current;
        if (newStatus != null) updated = updated.withStatus(newStatus);
        if (newAssignedTo != null) updated = updated.withAssignedTo(newAssignedTo);
        if (newDesc != null) updated = updated.withDescription(newDesc);
        tasks.put(id, updated);
        try { save(); } catch (IOException e) {
            throw new RuntimeException("更新任务失败: " + e.getMessage(), e);
        }
        return updated;
    }

    /** 移除任务 */
    public void removeTask(String id) {
        // 检查是否有其他任务依赖此任务
        for (SharedTask t : tasks.values()) {
            if (t.dependsOn().contains(id)) {
                throw new IllegalArgumentException(
                    "无法删除任务 " + id + "：被任务 " + t.id() + " 依赖");
            }
        }
        tasks.remove(id);
        try { save(); } catch (IOException e) {
            throw new RuntimeException("删除任务失败: " + e.getMessage(), e);
        }
    }

    /** 返回所有依赖已满足的就绪任务 */
    public List<SharedTask> readyTasks() {
        Set<String> doneIds = new HashSet<>();
        for (SharedTask t : tasks.values()) {
            if (t.status() == TaskStatus.DONE) doneIds.add(t.id());
        }
        return tasks.values().stream()
            .filter(t -> t.status() == TaskStatus.TODO)
            .filter(t -> doneIds.containsAll(t.dependsOn()))
            .toList();
    }

    /** 所有任务 */
    public List<SharedTask> all() {
        return List.copyOf(tasks.values());
    }

    public int size() { return tasks.size(); }

    // ========== JSON 序列化 ==========

    @SuppressWarnings("unchecked")
    private SharedTask parseTask(String line) throws IOException {
        Map<String, Object> m = json.readValue(line, Map.class);
        return new SharedTask(
            (String) m.get("id"),
            (String) m.get("title"),
            (String) m.get("description"),
            TaskStatus.valueOf((String) m.get("status")),
            (List<String>) m.getOrDefault("dependsOn", List.of()),
            (String) m.get("assignedTo"),
            ((Number) m.get("createdAt")).longValue(),
            ((Number) m.get("updatedAt")).longValue()
        );
    }

    private String toJson(SharedTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("title", t.title());
        m.put("description", t.description());
        m.put("status", t.status().name());
        m.put("dependsOn", t.dependsOn());
        m.put("assignedTo", t.assignedTo());
        m.put("createdAt", t.createdAt());
        m.put("updatedAt", t.updatedAt());
        try { return json.writeValueAsString(m); }
        catch (IOException e) { throw new RuntimeException(e); }
    }
}
