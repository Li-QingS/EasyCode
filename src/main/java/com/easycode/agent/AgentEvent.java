package com.easycode.agent;

import com.easycode.tool.ToolResult;
import com.easycode.context.CompressEvent;
import java.util.concurrent.CompletableFuture;

public sealed interface AgentEvent {
    record TextDelta(String text) implements AgentEvent {}
    record ToolCallStart(int index, String toolId, String toolName) implements AgentEvent {}
    record ToolCallEnd(int index, String toolId, String toolName, ToolResult result) implements AgentEvent {}
    record TokenUsage(int roundInput, int roundOutput, int totalInput, int totalOutput, int cacheWrite, int cacheRead) implements AgentEvent {}
    record IterationProgress(int round, int maxRounds) implements AgentEvent {}
    record RoundComplete(int round) implements AgentEvent {}
    record Error(String message, boolean fatal) implements AgentEvent {}
    record PermissionAsk(String toolId, String toolName, String preview, String reason, CompletableFuture<String> future) implements AgentEvent {}
    record AgentFinished(String finalText, int totalRounds, int totalInputTokens, int totalOutputTokens) implements AgentEvent {}
    record ContextCompress(CompressEvent event) implements AgentEvent {}
}
