package com.easycode;

import com.easycode.agent.AgentLoop;
import com.easycode.hook.HookConfig;
import com.easycode.hook.HookEngine;
import com.easycode.subagent.AgentDefLoader;
import com.easycode.subagent.RunAgentTool;
import com.easycode.subagent.TaskManager;
import com.easycode.subagent.WorktreeManager;
import com.easycode.team.TeamPersistence;
import com.easycode.session.SessionContext;
import com.easycode.instructions.InstructionLoader;
import com.easycode.memory.MemoryInjector;
import com.easycode.session.SessionWriter;
import com.easycode.session.SessionCleaner;
import com.easycode.skill.SkillLoader;
import com.easycode.skill.SkillRegistry;
import com.easycode.skill.LoadSkillTool;
import com.easycode.skill.SkillWatcher;
import com.easycode.config.Config;
import com.easycode.config.ConfigLoader;
import com.easycode.conversation.ConversationMgr;
import com.easycode.permission.PermissionConfig;
import com.easycode.permission.PermissionPipeline;
import com.easycode.provider.LlmProvider;
import com.easycode.provider.ProviderFactory;
import com.easycode.command.CommandDispatcher;
import com.easycode.command.CommandRegistry;
import com.easycode.tui.Tui;
import com.easycode.tool.*;
import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) {
        try {
            Config config = ConfigLoader.load("easycode.yaml");
            LlmProvider provider = ProviderFactory.create(config);
            ToolRegistry registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new WriteFileTool());
            registry.register(new EditFileTool());
            registry.register(new ExecCommandTool());
            registry.register(new FindFilesTool());
            registry.register(new GrepCodeTool());
            String sessionId = SessionContext.newSessionId();
            SessionWriter writer = new SessionWriter(SessionContext.jsonlPath(sessionId));
            ConversationMgr conversation = new ConversationMgr(
                msg -> writer.append(msg, config.model()),      // onAppend
                msgs -> { writer.appendCompactMarker();          // onReplace: 先写 compact 标记
                          for (var m : msgs) writer.append(m, config.model()); }
            );
            Runtime.getRuntime().addShutdownHook(new Thread(writer::close, "session-writer"));
            SessionCleaner.clean(Path.of(".easycode/sessions"));
            PermissionPipeline pipeline = new PermissionPipeline(PermissionConfig.load(Path.of("").toAbsolutePath()));
            com.easycode.mcp.McpManager mcpMgr = com.easycode.mcp.McpManager.discoverAndRegister(registry, com.easycode.mcp.McpConfigLoader.merge(com.easycode.mcp.McpConfigLoader.load(java.nio.file.Path.of("").toAbsolutePath()), config.mcpServers()));
            Runtime.getRuntime().addShutdownHook(new Thread(mcpMgr::close, "mcp-shutdown"));

            // Skill 系统初始化
            SkillLoader skillLoader = new SkillLoader(registry, Path.of("").toAbsolutePath());
            java.util.List<com.easycode.skill.SkillFrontmatter> frontmatters = skillLoader.loadAll();
            skillLoader.validateAllowedTools(frontmatters);
            SkillRegistry skillRegistry = new SkillRegistry(registry, skillLoader);
            skillRegistry.initialize(frontmatters);
            LoadSkillTool loadSkillTool = new LoadSkillTool(skillRegistry);
            registry.register(loadSkillTool);

            // Hook 系统初始化
            var hookRules = HookConfig.load(Path.of("").toAbsolutePath());
            HookEngine hookEngine = new HookEngine(hookRules);
            hookEngine.fire(com.easycode.hook.HookEvent.STARTUP, java.util.Map.of());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                hookEngine.fire(com.easycode.hook.HookEvent.SHUTDOWN, java.util.Map.of());
                hookEngine.shutdown();
            }, "hook-shutdown"));

            // 子 Agent 系统初始化
            var agentDefs = AgentDefLoader.loadAll(Path.of("").toAbsolutePath());
            System.err.println("[main] loaded " + agentDefs.size() + " agent definitions");
            TaskManager taskManager = new TaskManager();
            Runtime.getRuntime().addShutdownHook(new Thread(taskManager::shutdown, "taskmanager-shutdown"));
            WorktreeManager worktreeManager = new WorktreeManager(Path.of("").toAbsolutePath());
            worktreeManager.cleanExpired(24 * 60 * 60 * 1000);

            // Team Lead 系统初始化
            try {
                var teamNames = TeamPersistence.listTeams();
                if (!teamNames.isEmpty()) {
                    System.err.println("[main] found " + teamNames.size() + " team(s): " + String.join(", ", teamNames));
                }
            } catch (Exception e) {
                System.err.println("[main] team init skipped: " + e.getMessage());
            }

            RunAgentTool runAgentTool = new RunAgentTool(agentDefs, registry, provider, config, hookEngine,
                conversation::getHistory, taskManager, worktreeManager);
            registry.register(runAgentTool);

            // 注入 HookEngine 到 ToolExecutor

            String instructions = InstructionLoader.load(Path.of("").toAbsolutePath());
            String skillsText = skillRegistry.buildAvailableSkillsText();
            if (!skillsText.isEmpty()) {
                instructions = instructions + "\n\n" + skillsText;
            }
            String memoryIndex = MemoryInjector.build(
                Path.of(".easycode/memory"),
                Path.of(System.getProperty("user.home"), ".easycode/memory"));
            CommandRegistry cmdRegistry = new CommandRegistry();
            CommandDispatcher dispatcher = new CommandDispatcher(cmdRegistry, null);
            AgentLoop agentLoop = new AgentLoop(provider, registry, conversation, config, "1.0.0",
                instructions, memoryIndex, skillRegistry, hookEngine);
            // 注入 HookEngine 到 ToolExecutor（静态工具类）
            com.easycode.agent.ToolExecutor.setHookEngine(hookEngine);
            Tui tui = new Tui(agentLoop, registry, conversation, config, pipeline, dispatcher, sessionId);
            dispatcher.setUi(tui);
            dispatcher.setSkillRegistry(skillRegistry);
            dispatcher.setSkillContext(conversation, registry, provider, config);
            dispatcher.registerSkillCommands();
            dispatcher.registerBuiltins();

            // 启动热更新监视
            SkillWatcher watcher = new SkillWatcher(skillLoader, skillRegistry, dispatcher,
                Path.of("").toAbsolutePath());
            watcher.start();
            Runtime.getRuntime().addShutdownHook(new Thread(watcher::stop, "skill-watcher-shutdown"));
            tui.start();
        } catch (Exception e) {
            java.lang.System.err.println("启动失败: " + e.getMessage());
            java.lang.System.exit(1);
        }
    }
}
