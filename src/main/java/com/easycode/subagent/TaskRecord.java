package com.easycode.subagent;

/** 子 Agent 任务记录 */
public record TaskRecord(
    String id,
    String agentName,
    TaskStatus status,
    String output,
    int turnsUsed,
    int inputTokens,
    int outputTokens,
    long startTimeMs,
    long endTimeMs,
    String worktreeRoot
) {
    /** 向后兼容：无 worktree 的构造器 */
    public TaskRecord(String id, String agentName, TaskStatus status,
                      String output, int turnsUsed, int inputTokens,
                      int outputTokens, long startTimeMs, long endTimeMs) {
        this(id, agentName, status, output, turnsUsed, inputTokens,
            outputTokens, startTimeMs, endTimeMs, null);
    }

    public TaskRecord withStatus(TaskStatus newStatus) {
        return new TaskRecord(id, agentName, newStatus, output, turnsUsed,
            inputTokens, outputTokens, startTimeMs, endTimeMs, worktreeRoot);
    }

    public TaskRecord withWorktreeRoot(String wt) {
        return new TaskRecord(id, agentName, status, output, turnsUsed,
            inputTokens, outputTokens, startTimeMs, endTimeMs, wt);
    }

    public TaskRecord withOutput(String out) {
        return new TaskRecord(id, agentName, status, out, turnsUsed,
            inputTokens, outputTokens, startTimeMs, endTimeMs, worktreeRoot);
    }

    public TaskRecord withResult(String out, int turns, int inTok, int outTok) {
        return new TaskRecord(id, agentName, TaskStatus.DONE, out, turns,
            inTok, outTok, startTimeMs, System.currentTimeMillis(), worktreeRoot);
    }

    public TaskRecord withError(String error) {
        return new TaskRecord(id, agentName, TaskStatus.ERROR, error, turnsUsed,
            inputTokens, outputTokens, startTimeMs, System.currentTimeMillis(), worktreeRoot);
    }
}
