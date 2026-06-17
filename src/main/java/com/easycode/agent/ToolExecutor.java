package com.easycode.agent;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** 工具分批并发执行器：连续只读并发，有副作用串行，保持模型给出的顺序 */
public final class ToolExecutor {

    private static final int TOOL_TIMEOUT_SEC = 30;

    private ToolExecutor() {}

    /** 按安全性分批执行工具调用，结果按原始顺序返回 */
    public static List<ToolResult> executeAll(
            List<ToolCall> calls,
            ToolRegistry registry,
            Consumer<AgentEvent> eventSink) {

        int n = calls.size();
        List<ToolResult> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(null);
        }

        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            List<Integer> readBatchIndices = new ArrayList<>();

            for (int i = 0; i < n; i++) {
                ToolCall call = calls.get(i);
                Tool.Permission perm;
                try {
                    perm = registry.getPermission(call.name());
                } catch (IllegalArgumentException e) {
                    ToolResult err = ToolResult.err(call.name(), "未知工具: " + call.name(), 0);
                    results.set(i, err);
                    eventSink.accept(new AgentEvent.ToolCallEnd(i, call.id(), call.name(), err));
                    continue;
                }

                if (perm == Tool.Permission.READ_ONLY) {
                    readBatchIndices.add(i);
                } else {
                    // 副作用工具：先排空当前只读批（并发），再串行执行当前调用
                    drainBatch(readBatchIndices, calls, registry, results, eventSink, executor);
                    executeOne(call, i, registry, results, eventSink, executor);
                }
            }

            // 排空最后的只读批
            drainBatch(readBatchIndices, calls, registry, results, eventSink, executor);
        } finally {
            executor.shutdownNow();
        }

        return results;
    }

    /** 并发执行只读批，结果按原始顺序收集 */
    private static void drainBatch(
            List<Integer> indices, List<ToolCall> calls, ToolRegistry registry,
            List<ToolResult> results, Consumer<AgentEvent> eventSink,
            ExecutorService executor) {
        if (indices.isEmpty()) return;

        // 提交所有只读工具到共享线程池
        List<Future<ToolResult>> futures = new ArrayList<>();
        for (int idx : indices) {
            ToolCall call = calls.get(idx);
            futures.add(executor.submit(() -> executeOneTool(call, registry)));
        }

        // 按原始顺序收集结果，每个受 30s 超时约束
        for (int j = 0; j < indices.size(); j++) {
            int idx = indices.get(j);
            ToolCall call = calls.get(idx);
            ToolResult result;
            try {
                result = futures.get(j).get(TOOL_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                futures.get(j).cancel(true);
                result = ToolResult.err(call.name(), "超时（>" + TOOL_TIMEOUT_SEC + "s）", 0);
            } catch (Exception e) {
                result = ToolResult.err(call.name(), "执行失败: " + e.getMessage(), 0);
            }
            results.set(idx, result);
            eventSink.accept(new AgentEvent.ToolCallEnd(idx, call.id(), call.name(), result));
        }

        indices.clear();
    }

    /** 串行执行单个副作用工具，通过线程池提交以复用超时机制 */
    private static void executeOne(
            ToolCall call, int index, ToolRegistry registry,
            List<ToolResult> results, Consumer<AgentEvent> eventSink,
            ExecutorService executor) {
        Future<ToolResult> future = executor.submit(() -> executeOneTool(call, registry));
        ToolResult result;
        try {
            result = future.get(TOOL_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            result = ToolResult.err(call.name(), "超时（>" + TOOL_TIMEOUT_SEC + "s）", 0);
        } catch (Exception e) {
            result = ToolResult.err(call.name(), "执行失败: " + e.getMessage(), 0);
        }
        results.set(index, result);
        eventSink.accept(new AgentEvent.ToolCallEnd(index, call.id(), call.name(), result));
    }

    /** 直接执行工具调用（由线程池调度，不含超时逻辑） */
    private static ToolResult executeOneTool(ToolCall call, ToolRegistry registry) {
        try {
            Tool tool = registry.get(call.name());
            return tool.execute(call.input());
        } catch (Exception e) {
            return ToolResult.err(call.name(), "工具执行异常: " + e.getMessage(), 0);
        }
    }
}
