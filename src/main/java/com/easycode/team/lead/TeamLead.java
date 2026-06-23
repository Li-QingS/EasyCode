package com.easycode.team.lead;

import com.easycode.config.Config;
import com.easycode.hook.HookEngine;
import com.easycode.provider.LlmProvider;
import com.easycode.subagent.AgentDef;
import com.easycode.subagent.WorktreeManager;
import com.easycode.team.*;
import com.easycode.team.runtime.InProcessRuntime;
import com.easycode.team.runtime.MemberRuntime;
import com.easycode.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Team Lead 编排器：拆目标→派生成员→合并结果 */
public class TeamLead {

    private final Team team;
    private final SharedTaskList taskList;
    private final NameRegistry nameRegistry;
    private final LlmProvider provider;
    private final Config config;
    private final HookEngine hookEngine;
    private final ToolRegistry parentTools;
    private final Path projectRoot;

    private final List<MemberRuntime> runtimes = new ArrayList<>();

    public TeamLead(Team team, SharedTaskList taskList, NameRegistry nameRegistry,
                    LlmProvider provider, Config config, HookEngine hookEngine,
                    ToolRegistry parentTools, Path projectRoot) {
        this.team = team;
        this.taskList = taskList;
        this.nameRegistry = nameRegistry;
        this.provider = provider;
        this.config = config;
        this.hookEngine = hookEngine;
        this.parentTools = parentTools;
        this.projectRoot = projectRoot;
    }

    /** 领导一个目标：拆任务→派生成员→合并 */
    public String lead(String userGoal) throws IOException {
        StringBuilder log = new StringBuilder();

        // 1. 拆目标为带依赖任务（简单策略：串行依赖）
        log.append("## 目标: ").append(userGoal).append("\n");
        String id1 = taskList.addTask("分析需求", "理解并分析: " + userGoal, List.of(), null).id();
        String id2 = taskList.addTask("设计方案", "根据需求分析设计技术方案", List.of(id1), null).id();
        String id3 = taskList.addTask("实现代码", "根据技术方案编写代码", List.of(id2), null).id();
        log.append("创建 ").append(taskList.size()).append(" 个任务\n");

        // 2. 为每个成员创建邮箱
        for (TeamMember m : team.members()) {
            nameRegistry.register(m.name(), m.mailboxPath());
        }
        // Lead 自己也注册
        nameRegistry.register("lead", team.storageDir().resolve("mailboxes").resolve("lead.jsonl"));
        Path leadMailboxFile = nameRegistry.resolve("lead");
        if (!Files.exists(leadMailboxFile)) Files.createFile(leadMailboxFile);
        Mailbox leadMailbox = new Mailbox(leadMailboxFile);

        // 3. 派生所有成员
        log.append("派生 ").append(team.members().size()).append(" 个成员\n");
        for (TeamMember m : team.members()) {
            InProcessRuntime runtime = new InProcessRuntime(m, taskList,
                nameRegistry, new Mailbox(m.mailboxPath()),
                provider, config, hookEngine, parentTools, projectRoot);
            runtime.start();
            runtimes.add(runtime);
            log.append("  - ").append(m.name()).append(" 已启动 (session=")
                .append(runtime.getSessionId()).append(")\n");
        }

        // 4. 等待所有成员完成
        log.append("等待成员完成...\n");
        waitForAll(300); // 最多等 5 分钟

        // 5. git merge
        log.append("\n## 合并结果\n");
        String mergeResult = gitMerge();
        log.append(mergeResult);

        return log.toString();
    }

    /** 等待所有成员空闲 */
    private void waitForAll(int maxWaitSec) {
        long deadline = System.currentTimeMillis() + maxWaitSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            boolean allIdle = runtimes.stream()
                .allMatch(r -> r.getStatus() == MemberStatus.IDLE);
            if (allIdle) break;
            try { Thread.sleep(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    /** Git merge 各成员工作目录 */
    private String gitMerge() {
        StringBuilder result = new StringBuilder();
        List<Path> memberDirs = team.members().stream()
            .map(TeamMember::workDir)
            .filter(Objects::nonNull)
            .filter(Files::isDirectory)
            .toList();

        if (memberDirs.isEmpty()) {
            result.append("无成员工作目录需要合并\n");
            return result.toString();
        }

        for (Path dir : memberDirs) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "git", "-C", projectRoot.toString(),
                    "merge", "--no-commit", "--no-ff", "-X", "theirs",
                    "easycode/" + WorktreeManager.slug(dir.getFileName().toString()));
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                int exit = p.waitFor();

                if (exit == 0) {
                    // 无冲突，提交
                    new ProcessBuilder("git", "-C", projectRoot.toString(),
                        "commit", "-m", "easycode: merge worktree " + dir.getFileName())
                        .start().waitFor();
                    result.append("✅ 合并 ").append(dir.getFileName()).append(" 成功\n");
                } else {
                    // 有冲突，回滚
                    new ProcessBuilder("git", "-C", projectRoot.toString(),
                        "merge", "--abort").start().waitFor();
                    result.append("❌ 合并 ").append(dir.getFileName())
                        .append(" 有冲突，已回滚:\n").append(output).append("\n");
                }
            } catch (Exception e) {
                result.append("❌ 合并 ").append(dir.getFileName())
                    .append(" 异常: ").append(e.getMessage()).append("\n");
            }
        }
        return result.toString();
    }

    /** 获取所有运行时 */
    public List<MemberRuntime> runtimes() { return Collections.unmodifiableList(runtimes); }

    /** 停止所有成员 */
    public void shutdown() {
        for (MemberRuntime r : runtimes) r.stop();
    }
}
