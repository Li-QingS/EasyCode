package com.easycode;

import com.easycode.agent.AgentLoop;
import com.easycode.config.Config;
import com.easycode.config.ConfigLoader;
import com.easycode.conversation.ConversationMgr;
import com.easycode.provider.LlmProvider;
import com.easycode.provider.ProviderFactory;
import com.easycode.tui.Tui;

import com.easycode.tool.*;

/** EasyCode 主入口 */
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
            ConversationMgr conversation = new ConversationMgr();
            AgentLoop agentLoop = new AgentLoop(provider, registry, conversation, config, "1.0.0");
            Tui tui = new Tui(agentLoop, registry, conversation, config);
            tui.start();
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
