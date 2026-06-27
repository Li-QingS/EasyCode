package com.easycode.subagent;

import com.easycode.config.Config;
import com.easycode.conversation.MessageRecord;
import com.easycode.hook.HookEngine;
import com.easycode.provider.LlmProvider;
import com.easycode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeoutException;

/** 统一的 run_agent 工具 */
public class RunAgentTool implements Tool {

    private static final ObjectMapper json = new ObjectMapper();

    private final Map<String, AgentDef> agentDefs;
    private final ToolRegistry parentTools;
    private final LlmProvider provider;
    private final Config config;
    private final HookEngine hookEngine;
    private final ConversationAccessor conversationAccessor;
    private final TaskManager taskManager;
    private final WorktreeManager worktreeManager;

    @FunctionalInterface
    public interface ConversationAccessor {
        List<MessageRecord> getHistory();
    }

    public RunAgentTool(Map<String, AgentDef> agentDefs, ToolRegistry parentTools,
                        LlmProvider provider, Config config, HookEngine hookEngine,
                        ConversationAccessor conversationAccessor,
                        TaskManager taskManager) {
        this(agentDefs, parentTools, provider, config, hookEngine, conversationAccessor,
            taskManager, null);
    }

    public RunAgentTool(Map<String, AgentDef> agentDefs, ToolRegistry parentTools,
                        LlmProvider provider, Config config, HookEngine hookEngine,
                        ConversationAccessor conversationAccessor,
                        TaskManager taskManager, WorktreeManager worktreeManager) {
        this.agentDefs = agentDefs;
        this.parentTools = parentTools;
        this.provider = provider;
        this.config = config;
        this.hookEngine = hookEngine;
        this.conversationAccessor = conversationAccessor;
        this.taskManager = taskManager;
        this.worktreeManager = worktreeManager;
    }

    @Override public String name() { return "run_agent"; }

    @Override public String description() {
        return "委派子任务给独立的子 Agent。defined 模式使用预定义角色，fork 模式继承当前对话历史。";
    }

    @Override public Tool.Category category() { return Tool.Category.SHELL; }

    @Override public Tool.Permission permission() { return Tool.Permission.READ_WRITE; }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode mode = props.putObject("mode");
        mode.put("type", "string").put("description", "defined 或 fork");
        ObjectNode name = props.putObject("name");
        name.put("type", "string").put("description", "defined 模式的角色名");
        ObjectNode prompt = props.putObject("prompt");
        prompt.put("type", "string").put("description", "子任务的描述");
        ObjectNode bg = props.putObject("background");
        bg.put("type", "boolean").put("description", "是否后台运行（fork 强制后台）");
        schema.putArray("required").add("mode");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode input) {
        String mode = input.has("mode") ? input.get("mode").asText() : "";
        String agentName = input.has("name") ? input.get("name").asText() : null;
        String prompt = input.has("prompt") ? input.get("prompt").asText() : "";
        boolean background = input.has("background") && input.get("background").asBoolean();

        if (!"defined".equals(mode) && !"fork".equals(mode)) {
            return ToolResult.err("run_agent", "mode 必须是 defined 或 fork", 0);
        }

        if ("defined".equals(mode) && (agentName == null || agentName.isBlank())) {
            return ToolResult.err("run_agent", "defined 模式需要指定 name", 0);
        }

        AgentDef def;
        if ("defined".equals(mode)) {
            def = agentDefs.get(agentName);
            if (def == null) {
                return ToolResult.err("run_agent",
                    "未找到角色 '" + agentName + "'。可用: " + String.join(", ", agentDefs.keySet()), 0);
            }
        } else {
            def = new AgentDef("fork", "Fork 子 Agent",
                "你是主 Agent 的子进程，继承对话历史。请根据 prompt 完成任务。",
                List.of(), List.of(), "", 10, "", "none", 120);
        }

        // Worktree 隔离
        Path worktreeRoot = null;
        String worktreeInfo = "";
        if ("worktree".equals(def.isolation()) && worktreeManager != null && "defined".equals(mode)) {
            String slug = WorktreeManager.slug(def.name());
            if (!WorktreeManager.isValidSlug(slug)) {
                return ToolResult.err("run_agent", "非法的 Worktree slug: " + slug, 0);
            }
            try {
                worktreeRoot = worktreeManager.create(slug);
                worktreeInfo = " [worktree: " + worktreeRoot + "]";
            } catch (Exception e) {
                return ToolResult.err("run_agent", "Worktree 创建失败: " + e.getMessage(), 0);
            }
        }

        ToolRegistry filteredTools = buildFiltered(def);
        List<MessageRecord> seeds = "fork".equals(mode)
            ? new ArrayList<>(conversationAccessor.getHistory()) : List.of();
        // 所有模式统一后台运行——不再强制 fork 为 background

        String taskId;
        taskId = UUID.randomUUID().toString().substring(0, 8);
        SubAgent subAgent = new SubAgent(taskId, def, prompt, seeds,
            provider, config, hookEngine, filteredTools, worktreeRoot);

        String displayName = "defined".equals(mode) ? agentName : "fork";
        String envInfo = "委派子 Agent [" + displayName + "] (mode=" + mode
            + ", bg=" + background + ", taskId=" + taskId + ")" + worktreeInfo;
        System.err.println("[run_agent] " + envInfo);

        taskId = taskManager.submit(subAgent, background, taskId);
        if (worktreeRoot != null) taskManager.setWorktree(taskId, worktreeRoot);
        System.err.println("[run_agent] submitted taskId=" + taskId);

        // 所有子 Agent 统一后台运行——不阻塞主 Agent 循环
        // 结果由 AgentLoop 的 drainCompleted() 在下轮自动注入
        if (!background) {
            // 非后台模式也返回 taskId，主 Agent 通过 drainCompleted 获取结果
            return ToolResult.ok("run_agent",
                envInfo + "\n任务已提交，taskId=" + taskId
                + "\n（结果将在下轮自动呈现）", taskId.length());
        }

        return ToolResult.ok("run_agent",
            envInfo + "\ntaskId=" + taskId + "，任务已在后台启动。", taskId.length());
    }

    /** 子 Agent 退出后检查 Worktree 变更，决定保留或清理 */
    private ToolResult cleanupWorktree(ToolResult result, Path worktreeRoot) {
        if (worktreeRoot == null || worktreeManager == null) return result;
        if (!worktreeManager.hasChanges(worktreeRoot)) {
            worktreeManager.remove(worktreeRoot);
            return result;
        }
        // 有变更：保留 + 提示
        String content = result.content() != null ? result.content() : "";
        String updated = content + "\n\n[worktree] 保留: " + worktreeRoot;
        return result.success()
            ? ToolResult.ok(result.toolName(), updated, updated.length())
            : ToolResult.err(result.toolName(), updated, updated.length());
    }

    private ToolRegistry buildFiltered(AgentDef def) {
        ToolRegistry filtered = new ToolRegistry();
        Set<String> deny = new HashSet<>(def.toolsDeny());
        deny.add("run_agent"); // 全局禁止嵌套
        for (var tool : parentTools.all()) {
            if (!deny.contains(tool.name())) {
                filtered.register(tool);
            }
        }
        if (!def.toolsAllow().isEmpty()) {
            ToolRegistry whitelisted = new ToolRegistry();
            for (String name : def.toolsAllow()) {
                if (deny.contains(name)) continue;
                try {
                    whitelisted.register(filtered.get(name));
                } catch (IllegalArgumentException ignored) {}
            }
            return whitelisted;
        }
        return filtered;
    }

    private ToolResult formatResult(TaskRecord result, String envInfo) {
        if (result == null) return ToolResult.err("run_agent", envInfo + "\n错误: 无结果", 0);
        return switch (result.status()) {
            case DONE -> ToolResult.ok("run_agent",
                envInfo + "\n--- 输出 ---\n" + result.output()
                + "\n--- 统计 ---\n轮次:" + result.turnsUsed()
                + " 入:" + result.inputTokens() + "t 出:" + result.outputTokens() + "t",
                result.output().length());
            case ERROR -> ToolResult.err("run_agent", envInfo + "\n错误: " + result.output(), 0);
            case TIMEOUT -> ToolResult.ok("run_agent", envInfo + "\n" + result.output(), 0);
            default -> ToolResult.ok("run_agent", envInfo + "\n状态: " + result.status(), 0);
        };
    }
}
