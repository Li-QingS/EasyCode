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
    long endTimeMs
) {
    public TaskRecord withStatus(TaskStatus newStatus) {
        return new TaskRecord(id, agentName, newStatus, output, turnsUsed,
            inputTokens, outputTokens, startTimeMs, endTimeMs);
    }

    public TaskRecord withResult(String out, int turns, int inTok, int outTok) {
        return new TaskRecord(id, agentName, TaskStatus.DONE, out, turns,
            inTok, outTok, startTimeMs, System.currentTimeMillis());
    }

    public TaskRecord withError(String error) {
        return new TaskRecord(id, agentName, TaskStatus.ERROR, error, turnsUsed,
            inputTokens, outputTokens, startTimeMs, System.currentTimeMillis());
    }
}
