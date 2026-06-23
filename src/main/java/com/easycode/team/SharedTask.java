package com.easycode.team;

import java.util.List;
import java.util.UUID;

/** 共享任务 */
public record SharedTask(
    String id,
    String title,
    String description,
    TaskStatus status,
    List<String> dependsOn,
    String assignedTo,
    long createdAt,
    long updatedAt
) {
    public SharedTask {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString().substring(0, 8);
        if (status == null) status = TaskStatus.TODO;
        if (dependsOn == null) dependsOn = List.of();
        if (title == null) title = "";
        if (description == null) description = "";
        if (createdAt <= 0) createdAt = System.currentTimeMillis();
        if (updatedAt <= 0) updatedAt = createdAt;
    }

    /** 便捷构造：自动补 id + 时间戳 */
    public SharedTask(String title, String description, List<String> dependsOn, String assignedTo) {
        this(UUID.randomUUID().toString().substring(0, 8), title, description,
            TaskStatus.TODO, dependsOn, assignedTo, System.currentTimeMillis(), System.currentTimeMillis());
    }

    /** 创建更新后的副本 */
    public SharedTask withStatus(TaskStatus newStatus) {
        return new SharedTask(id, title, description, newStatus, dependsOn, assignedTo, createdAt, System.currentTimeMillis());
    }

    public SharedTask withAssignedTo(String member) {
        return new SharedTask(id, title, description, status, dependsOn, member, createdAt, System.currentTimeMillis());
    }

    public SharedTask withDescription(String newDesc) {
        return new SharedTask(id, title, newDesc, status, dependsOn, assignedTo, createdAt, System.currentTimeMillis());
    }
}
