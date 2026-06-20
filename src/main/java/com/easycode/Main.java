package com.easycode;

import com.easycode.agent.AgentLoop;
import com.easycode.session.SessionContext;
import com.easycode.instructions.InstructionLoader;
import com.easycode.memory.MemoryInjector;
import com.easycode.session.SessionWriter;
import com.easycode.session.SessionCleaner;
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
            String instructions = InstructionLoader.load(Path.of("").toAbsolutePath());
            String memoryIndex = MemoryInjector.build(
                Path.of(".easycode/memory"),
                Path.of(System.getProperty("user.home"), ".easycode/memory"));
            CommandRegistry cmdRegistry = new CommandRegistry();
            CommandDispatcher dispatcher = new CommandDispatcher(cmdRegistry, null);
            AgentLoop agentLoop = new AgentLoop(provider, registry, conversation, config, "1.0.0",
                instructions, memoryIndex);
            Tui tui = new Tui(agentLoop, registry, conversation, config, pipeline, dispatcher, sessionId);
            dispatcher.setUi(tui);
            dispatcher.registerBuiltins();
            tui.start();
        } catch (Exception e) {
            java.lang.System.err.println("启动失败: " + e.getMessage());
            java.lang.System.exit(1);
        }
    }
}
