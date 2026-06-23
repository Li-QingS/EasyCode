package com.easycode.subagent;

import java.util.*;
import java.util.concurrent.*;

/** 后台任务管理器 */
public class TaskManager {

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<TaskRecord>> futures = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public TaskManager() {
        this.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    /** 提交子 Agent 任务 */
    public String submit(SubAgent subAgent, boolean background) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        TaskRecord pending = new TaskRecord(taskId, "?", TaskStatus.PENDING,
            "", 0, 0, 0, System.currentTimeMillis(), 0);
        tasks.put(taskId, pending);

        if (background) {
            Future<TaskRecord> future = executor.submit(() -> {
                tasks.put(taskId, pending.withStatus(TaskStatus.RUNNING));
                try {
                    TaskRecord result = subAgent.call();
                    tasks.put(taskId, result);
                    return result;
                } catch (Exception e) {
                    TaskRecord err = new TaskRecord(taskId, "?", TaskStatus.ERROR,
                        e.getMessage(), 0, 0, 0, pending.startTimeMs(), System.currentTimeMillis());
                    tasks.put(taskId, err);
                    return err;
                }
            });
            futures.put(taskId, future);
        } else {
            // 同步执行
            tasks.put(taskId, pending.withStatus(TaskStatus.RUNNING));
            try {
                TaskRecord result = subAgent.call();
                tasks.put(taskId, result);
            } catch (Exception e) {
                TaskRecord err = new TaskRecord(taskId, "?", TaskStatus.ERROR,
                    e.getMessage(), 0, 0, 0, pending.startTimeMs(), System.currentTimeMillis());
                tasks.put(taskId, err);
            }
        }

        return taskId;
    }

    /** 等待任务完成（同步模式） */
    public TaskRecord await(String taskId, long timeoutSec) throws TimeoutException {
        Future<TaskRecord> future = futures.get(taskId);
        if (future == null) {
            return tasks.getOrDefault(taskId, null);
        }
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            TaskRecord current = tasks.get(taskId);
            if (current != null && current.status() == TaskStatus.RUNNING) {
                TaskRecord timeout = new TaskRecord(taskId, current.agentName(),
                    TaskStatus.TIMEOUT, "[后台运行中] 任务仍在执行，稍后可用 /bg " + taskId + " 查看结果",
                    current.turnsUsed(), current.inputTokens(), current.outputTokens(),
                    current.startTimeMs(), System.currentTimeMillis());
                tasks.put(taskId, timeout);
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return tasks.getOrDefault(taskId, null);
        } catch (ExecutionException e) {
            TaskRecord err = new TaskRecord(taskId, "?", TaskStatus.ERROR,
                e.getMessage(), 0, 0, 0, 0, System.currentTimeMillis());
            tasks.put(taskId, err);
            return err;
        }
    }

    /** 查询任务状态 */
    public Optional<TaskRecord> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** 列出所有运行中的任务 */
    public List<TaskRecord> listRunning() {
        return tasks.values().stream()
            .filter(t -> t.status() == TaskStatus.RUNNING || t.status() == TaskStatus.PENDING)
            .toList();
    }

    /** 关闭线程池 */
    public void shutdown() {
        executor.shutdownNow();
    }
}
