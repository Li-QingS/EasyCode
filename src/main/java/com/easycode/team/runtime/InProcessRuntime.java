package com.easycode.team.runtime;

import com.easycode.agent.AgentLoop;
import com.easycode.config.Config;
import com.easycode.conversation.ConversationMgr;
import com.easycode.hook.HookEngine;
import com.easycode.provider.LlmProvider;
import com.easycode.subagent.AgentDef;
import com.easycode.subagent.SubAgent;
import com.easycode.subagent.TaskRecord;
import com.easycode.subagent.TaskStatus;
import com.easycode.team.*;
import com.easycode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** 同进程轻量成员执行 */
public class InProcessRuntime implements MemberRuntime {

    private final TeamMember member;
    private final SharedTaskList taskList;
    private final Mailbox mailbox;
    private final NameRegistry nameRegistry;
    private final LlmProvider provider;
    private final Config config;
    private final HookEngine hookEngine;
    private final ToolRegistry parentTools;
    private final Path projectRoot;

    private final AtomicReference<MemberStatus> status = new AtomicReference<>(MemberStatus.IDLE);
    private volatile String sessionId;
    private CompletableFuture<Void> future;

    public InProcessRuntime(TeamMember member, SharedTaskList taskList,
                            NameRegistry nameRegistry, Mailbox mailbox,
                            LlmProvider provider, Config config, HookEngine hookEngine,
                            ToolRegistry parentTools, Path projectRoot) {
        this.member = member;
        this.taskList = taskList;
        this.nameRegistry = nameRegistry;
        this.mailbox = mailbox;
        this.provider = provider;
        this.config = config;
        this.hookEngine = hookEngine;
        this.parentTools = parentTools;
        this.projectRoot = projectRoot;
    }

    @Override
    public void start() {
        if (status.get() != MemberStatus.IDLE) return;
        status.set(MemberStatus.WORKING);
        sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);

        future = CompletableFuture.runAsync(() -> {
            try {
                // 构建成员专属 ToolRegistry（注入协作工具）
                ToolRegistry memberTools = new ToolRegistry();
                // 文件工具
                for (var tool : parentTools.all()) {
                    // 排除 run_agent（成员不能再派生）
                    if (!"run_agent".equals(tool.name())) {
                        memberTools.register(tool);
                    }
                }
                // 协作工具（仅成员可见）
                memberTools.register(new TaskListTool(taskList));
                memberTools.register(new MailboxTool(mailbox, nameRegistry, member.name()));

                // 构建 AgentDef
                AgentDef def = new AgentDef(
                    member.name(), "团队成员",
                    "你是小组 [" + member.name() + "] 的成员。使用 team_task 查看和更新小组任务，使用 team_message 与其他成员沟通。",
                    List.of(), List.of(), "", 20, "", "none");

                // 构建 SubAgent 并执行
                Path workDir = member.workDir() != null ? member.workDir() : projectRoot;
                SubAgent subAgent = new SubAgent(
                    sessionId, def, "完成你被分配的任务", List.of(),
                    provider, config, hookEngine, memberTools, workDir);

                TaskRecord result = subAgent.call();
                // 完成后通知 Lead
                status.set(MemberStatus.IDLE);
                if (nameRegistry.contains("lead")) {
                    Path leadMailbox = nameRegistry.resolve("lead");
                    if (leadMailbox != null) {
                        Message statusMsg = new Message(member.name(),
                            "任务完成。状态: " + (result.status() == TaskStatus.DONE ? "成功" : "出错")
                            + "。输出摘要: " + result.output().substring(0, Math.min(200, result.output().length())),
                            MessageType.STATUS);
                        Mailbox.send(leadMailbox, statusMsg);
                    }
                }
            } catch (Exception e) {
                status.set(MemberStatus.IDLE);
            }
        });
    }

    @Override
    public void stop() {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        status.set(MemberStatus.IDLE);
    }

    @Override
    public boolean isRunning() {
        return status.get() == MemberStatus.WORKING;
    }

    @Override
    public MemberStatus getStatus() {
        return status.get();
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }
}
