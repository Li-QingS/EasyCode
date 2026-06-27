package com.easycode.hook;

import com.easycode.tool.ToolResult;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hook 调度引擎 */
public class HookEngine {

    private final List<HookRule> rules;
    private final Set<String> onceFired = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    public HookEngine(List<HookRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * 触发事件，返回需要拦截的 ToolResult（仅 PRE_TOOL 事件可能非空）。
     * 返回 Optional.empty() 表示不拦截。
     */
    public Optional<ToolResult> fire(HookEvent event, Map<String, Object> vars) {
        if (rules.isEmpty()) return Optional.empty();
        List<ToolResult> intercepts = new ArrayList<>();

        for (HookRule rule : rules) {
            if (rule.event() != event) continue;

            // once 检查
            if (rule.once() && onceFired.contains(rule.name())) continue;

            // 条件匹配
            if (!ConditionNode.matches(rule.condition(), vars)) {
                log(rule.name(), "condition not matched");
                continue;
            }

            // once 标记
            if (rule.once()) onceFired.add(rule.name());

            HookContext ctx = new HookContext(event, vars);

            if (rule.async()) {
                asyncExecutor.submit(() -> executeAction(rule, ctx));
            } else {
                try {
                    String output = rule.action().execute(ctx);
                    log(rule.name(), "ok, output=" + (output != null ? output.length() : 0) + " chars");
                    if (rule.event() == HookEvent.PRE_TOOL) {
                    if (output != null && !output.isBlank()) {
                        intercepts.add(ToolResult.err("hook:" + rule.name(), output, 0));
                    }
                    }
                } catch (Exception e) {
                    log(rule.name(), "FAILED: " + e.getMessage());
                }
            }
        }

        return intercepts.isEmpty() ? Optional.empty() : Optional.of(intercepts.get(0));
    }

    /** 获取提示词注入文本（PRE_LLM_REQUEST/SESSION_START/TURN_START 事件） */
    public String collectPrompts(HookEvent event, Map<String, Object> vars) {
        if (rules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (HookRule rule : rules) {
            if (rule.event() != event) continue;
            if (rule.once() && onceFired.contains(rule.name())) continue;
            if (!ConditionNode.matches(rule.condition(), vars)) continue;
            if (!(rule.action() instanceof PromptAction)) continue;
            if (rule.once()) onceFired.add(rule.name());
            try {
                String text = rule.action().execute(new HookContext(event, vars));
                if (text != null && !text.isBlank()) sb.append(text).append("\n");
            } catch (Exception e) {
                log(rule.name(), "prompt FAILED: " + e.getMessage());
            }
        }
        return sb.toString();
    }

    private void executeAction(HookRule rule, HookContext ctx) {
        try {
            rule.action().execute(ctx);
            log(rule.name(), "async ok");
        } catch (Exception e) {
            log(rule.name(), "async FAILED: " + e.getMessage());
        }
    }

    private static void log(String ruleName, String msg) {
        System.err.println("[hook] " + ruleName + ": " + msg);
    }

    /** 进程结束时关闭线程池 */
    public void shutdown() {
        asyncExecutor.shutdownNow();
    }
}
