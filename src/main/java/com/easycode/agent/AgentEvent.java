package com.easycode.agent;

import com.easycode.tool.ToolResult;

/** Agent 与界面解耦的事件族，通过 sealed interface 保证事件类型穷举 */
public sealed interface AgentEvent {

    /** 文本增量，实时推给界面 */
    record TextDelta(String text) implements AgentEvent {}

    /** 工具调用开始 */
    record ToolCallStart(int index, String toolId, String toolName) implements AgentEvent {}

    /** 工具调用结束，含执行结果 */
    record ToolCallEnd(int index, String toolId, String toolName, ToolResult result) implements AgentEvent {}

    /** Token 用量更新 */
    record TokenUsage(int roundInput, int roundOutput, int totalInput, int totalOutput, int cacheWrite, int cacheRead) implements AgentEvent {}

    /** 迭代进度更新 */
    record IterationProgress(int round, int maxRounds) implements AgentEvent {}

    /** 单轮结束 */
    record RoundComplete(int round) implements AgentEvent {}

    /** 错误事件，fatal 为 true 时循环终止 */
    record Error(String message, boolean fatal) implements AgentEvent {}

    /** Agent 循环结束，finalText 为 null 表示非正常终止 */
    record AgentFinished(String finalText, int totalRounds, int totalInputTokens, int totalOutputTokens)
            implements AgentEvent {}
}
