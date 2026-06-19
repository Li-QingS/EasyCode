package com.easycode.agent;

import com.easycode.permission.PermissionContext;
import com.easycode.permission.PermissionPipeline;
import com.easycode.permission.PermissionResult;
import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class ToolExecutor {

    private static final int TOOL_TIMEOUT_SEC = 30;

    private ToolExecutor() {}

    public static List<ToolResult> executeAll(
            List<ToolCall> calls, ToolRegistry registry,
            Consumer<AgentEvent> eventSink, PermissionPipeline pipeline, PermissionContext permCtx) {

        int n = calls.size();
        List<ToolResult> results = new ArrayList<>();
        for (int i = 0; i < n; i++) results.add(null);

        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            List<Integer> readBatchIndices = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ToolCall call = calls.get(i);
                Tool.Permission perm;
                try { perm = registry.getPermission(call.name()); }
                catch (IllegalArgumentException e) {
                    results.set(i, ToolResult.err(call.name(), "未知工具: " + call.name(), 0));
                    eventSink.accept(new AgentEvent.ToolCallEnd(i, call.id(), call.name(), results.get(i)));
                    continue;
                }
                if (perm == Tool.Permission.READ_ONLY) {
                    readBatchIndices.add(i);
                } else {
                    drainBatch(readBatchIndices, calls, registry, results, eventSink, executor, pipeline, permCtx);
                    executeOne(call, i, registry, results, eventSink, executor, pipeline, permCtx);
                }
            }
            drainBatch(readBatchIndices, calls, registry, results, eventSink, executor, pipeline, permCtx);
        } finally {
            executor.shutdownNow();
        }
        return results;
    }

    private static void drainBatch(List<Integer> indices, List<ToolCall> calls, ToolRegistry registry,
            List<ToolResult> results, Consumer<AgentEvent> eventSink, ExecutorService executor,
            PermissionPipeline pipeline, PermissionContext permCtx) {
        if (indices.isEmpty()) return;
        List<Future<ToolResult>> futures = new ArrayList<>();
        for (int idx : indices) {
            ToolCall call = calls.get(idx);
            futures.add(executor.submit(() -> executeOneTool(call, registry, pipeline, permCtx)));
        }
        for (int j = 0; j < indices.size(); j++) {
            int idx = indices.get(j);
            ToolCall call = calls.get(idx);
            ToolResult result;
            try { result = futures.get(j).get(TOOL_TIMEOUT_SEC, TimeUnit.SECONDS); }
            catch (TimeoutException e) { result = ToolResult.err(call.name(), "超时", 0); }
            catch (Exception e) { result = ToolResult.err(call.name(), "失败: " + e.getMessage(), 0); }
            results.set(idx, result);
            eventSink.accept(new AgentEvent.ToolCallEnd(idx, call.id(), call.name(), result));
        }
        indices.clear();
    }

    private static void executeOne(ToolCall call, int index, ToolRegistry registry,
            List<ToolResult> results, Consumer<AgentEvent> eventSink, ExecutorService executor,
            PermissionPipeline pipeline, PermissionContext permCtx) {
        Future<ToolResult> future = executor.submit(() -> executeOneTool(call, registry, pipeline, permCtx));
        ToolResult result;
        try { result = future.get(TOOL_TIMEOUT_SEC, TimeUnit.SECONDS); }
        catch (TimeoutException e) { result = ToolResult.err(call.name(), "超时", 0); }
        catch (Exception e) { result = ToolResult.err(call.name(), "失败: " + e.getMessage(), 0); }
        results.set(index, result);
        eventSink.accept(new AgentEvent.ToolCallEnd(index, call.id(), call.name(), result));
    }

    private static ToolResult executeOneTool(ToolCall call, ToolRegistry registry,
            PermissionPipeline pipeline, PermissionContext permCtx) {
        try {
            Tool tool = registry.get(call.name());
            // 权限检查
            PermissionResult pr = pipeline.check(call, tool, permCtx);
            if (pr == PermissionResult.DENY) {
                String reason = "权限拒绝";
                return ToolResult.err(call.name(), "权限拒绝: " + reason, 0);
            }
            if (pr == PermissionResult.ASK) {
                return ToolResult.askPending(call.name(), "需要用户确认: " + call.name());
            }
            return tool.execute(call.input());
        } catch (Exception e) {
            return ToolResult.err(call.name(), "工具执行异常: " + e.getMessage(), 0);
        }
    }
}
