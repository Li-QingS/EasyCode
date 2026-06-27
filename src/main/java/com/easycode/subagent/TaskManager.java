package com.easycode.subagent;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.*;

/** 后台任务管理器 */
public class TaskManager {

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<TaskRecord>> futures = new ConcurrentHashMap<>();
    private final Map<String, java.nio.file.Path> worktreeMap = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final WorktreeManager worktreeManager;

    public TaskManager() {
        this(null);
    }

    public TaskManager(WorktreeManager worktreeManager) {
        this.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        this.worktreeManager = worktreeManager;
    }

    /** 提交子 Agent 任务 */
    /** 提交子 Agent 任务。taskId 为空时自动生成 */
    public String submit(SubAgent subAgent, boolean background, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString().substring(0, 8);
        }
        final String tid = taskId;
        TaskRecord pending = new TaskRecord(tid, "?", TaskStatus.PENDING,
            "", 0, 0, 0, System.currentTimeMillis(), 0);
        tasks.put(tid, pending);

        if (background) {
            Future<TaskRecord> future = executor.submit(() -> {
                tasks.put(tid, pending.withStatus(TaskStatus.RUNNING));
                try {
                    TaskRecord result = subAgent.call();
                    tasks.put(tid, result);
                    return result;
                } catch (Exception e) {
                    TaskRecord err = new TaskRecord(tid, "?", TaskStatus.ERROR,
                        e.getMessage(), 0, 0, 0, pending.startTimeMs(), System.currentTimeMillis());
                    tasks.put(tid, err);
                    return err;
                }
            });
            futures.put(tid, future);
        } else {
            // 非后台模式也提交到 executor，通过 await() 等结果
            Future<TaskRecord> future = executor.submit(() -> {
                tasks.put(tid, pending.withStatus(TaskStatus.RUNNING));
                try {
                    TaskRecord result = subAgent.call();
                    tasks.put(tid, result);
                    return result;
                } catch (Exception e) {
                    TaskRecord err = new TaskRecord(tid, "?", TaskStatus.ERROR,
                        e.getMessage(), 0, 0, 0, pending.startTimeMs(), System.currentTimeMillis());
                    tasks.put(tid, err);
                    return err;
                }
            });
            futures.put(tid, future);
        }

        return tid;
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

    /** 取出并清除所有已完成/超时/错误的任务结果，同时清理无变更的 worktree */
    public List<TaskRecord> drainCompleted() {
        List<TaskRecord> completed = new ArrayList<>();
        var it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            TaskStatus s = e.getValue().status();
            if (s == TaskStatus.DONE || s == TaskStatus.ERROR || s == TaskStatus.TIMEOUT) {
                TaskRecord rec = e.getValue();
                rec = applyWorktreeCleanup(rec);
                completed.add(rec);
                it.remove();
            }
        }
        return completed;
    }

    /** 根据任务结果清理 worktree：无变更则删除，有变更则保留并在输出中提示 */
    private TaskRecord applyWorktreeCleanup(TaskRecord rec) {
        if (worktreeManager == null || rec.worktreeRoot() == null || rec.worktreeRoot().isBlank()) {
            return rec;
        }
        try {
            Path wtPath = Path.of(rec.worktreeRoot());
            if (!java.nio.file.Files.isDirectory(wtPath)) return rec;
            if (!worktreeManager.hasChanges(wtPath)) {
                worktreeManager.remove(wtPath);
                return rec;
            }
            // 有变更：保留 worktree，增强输出提示
            String suffix = "\n\n[worktree] 保留: " + rec.worktreeRoot()
                + "（检测到变更，未清理。可用 /clean-worktrees 手动清理）";
            return rec.withOutput(rec.output() + suffix);
        } catch (Exception ignored) {
            return rec;
        }
    }
    /** 列出所有任务（含已完成） */
    public List<TaskRecord> listAll() {
        return new ArrayList<>(tasks.values());
    }

    /** 记录 worktree 路径 */
    public void setWorktree(String taskId, java.nio.file.Path worktree) {
        if (worktree != null) worktreeMap.put(taskId, worktree);
    }
    /** 取出并移除 worktree 路径 */
    public java.nio.file.Path removeWorktree(String taskId) {
        return worktreeMap.remove(taskId);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
