package com.easycode.team;

import com.easycode.team.lead.CoordinatorMode;
import com.easycode.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** ch15 完整端到端场景测试 */
class TeamFullPipelineTest {

    private static final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SharedTaskList taskList;
    private NameRegistry nameRegistry;
    private Path mailboxesDir;

    @BeforeEach
    void setUp() throws Exception {
        Path tasksFile = tempDir.resolve("tasks.jsonl");
        Files.createFile(tasksFile);
        taskList = new SharedTaskList(tasksFile);

        mailboxesDir = tempDir.resolve("mailboxes");
        Files.createDirectories(mailboxesDir);

        nameRegistry = new NameRegistry();
    }

    // ===== AC1: 创建小组 → 拆目标 → 派生 =====
    @Test
    void ac1_createTeamAndDecomposeGoal() {
        // 1. 创建小组
        Team team = new Team("dev-team", "lead", tempDir);
        team.addMember(new TeamMember("coder-a", tempDir.resolve("a"), RuntimeBackend.IN_PROCESS, false));
        team.addMember(new TeamMember("coder-b", tempDir.resolve("b"), RuntimeBackend.IN_PROCESS, true));

        // 2. 拆目标为 3 个任务（A→B→C）
        SharedTask t1 = taskList.addTask("设计数据库", "设计用户表和权限表", List.of(), "coder-a");
        SharedTask t2 = taskList.addTask("实现 DAO 层", "基于数据库设计编写 DAO", List.of(t1.id()), null);
        SharedTask t3 = taskList.addTask("实现 Controller", "编写 REST API", List.of(t2.id()), "coder-b");

        assertEquals(3, taskList.size());
        assertEquals("coder-a", taskList.getTask(t1.id()).assignedTo());
        assertEquals("coder-b", taskList.getTask(t3.id()).assignedTo());

        // 3. 只有 t1 就绪（无依赖）
        List<SharedTask> ready = taskList.readyTasks();
        assertEquals(1, ready.size());
        assertEquals(t1.id(), ready.get(0).id());

        // 4. 完成 t1 → t2 就绪
        taskList.updateTask(t1.id(), TaskStatus.DONE, null, null);
        ready = taskList.readyTasks();
        assertEquals(1, ready.size());
        assertEquals(t2.id(), ready.get(0).id());

        // 5. 完成 t2 → t3 就绪
        taskList.updateTask(t2.id(), TaskStatus.DONE, null, null);
        ready = taskList.readyTasks();
        assertEquals(1, ready.size());
        assertEquals(t3.id(), ready.get(0).id());

        System.out.println("✅ AC1: Team created, goal decomposed into 3 tasks with dependencies");
    }

    // ===== AC2: 队员间点对点消息 =====
    @Test
    void ac2_memberToMemberMessages() throws Exception {
        // 注册成员邮箱
        Path aliceBox = mailboxesDir.resolve("alice.jsonl");
        Path bobBox = mailboxesDir.resolve("bob.jsonl");
        Files.createFile(aliceBox);
        Files.createFile(bobBox);
        nameRegistry.register("alice", aliceBox);
        nameRegistry.register("bob", bobBox);

        // Alice 完成任务，发消息给 Bob
        Mailbox.send(bobBox, new Message("alice", "DAO 层已完成，表结构: users(id, name, email)", MessageType.TEXT));

        // Bob 读到消息
        Mailbox bobMailbox = new Mailbox(bobBox);
        List<Message> bobMessages = bobMailbox.receive(true);
        assertEquals(1, bobMessages.size());
        assertTrue(bobMessages.get(0).body().contains("DAO 层已完成"));
        assertEquals("alice", bobMessages.get(0).sender());

        // Bob 回复
        Mailbox.send(aliceBox, new Message("bob", "收到，我接着写 Controller", MessageType.TEXT));
        Mailbox aliceMailbox = new Mailbox(aliceBox);
        assertEquals(1, aliceMailbox.receive(true).size());

        System.out.println("✅ AC2: Member-to-member messages work both ways");
    }

    // ===== AC3: 审批流 =====
    @Test
    void ac3_approvalFlow() throws Exception {
        // 注册 Lead 和成员
        Path leadBox = mailboxesDir.resolve("lead.jsonl");
        Path memberBox = mailboxesDir.resolve("coder.jsonl");
        Files.createFile(leadBox);
        Files.createFile(memberBox);
        nameRegistry.register("lead", leadBox);
        nameRegistry.register("coder", memberBox);

        // 成员发审批请求
        Mailbox.send(leadBox, new Message("coder",
            "需要审批：计划使用 HikariCP 连接池，最大连接数 20", MessageType.APPROVAL_REQUEST));

        // Lead 读审批请求
        Mailbox leadMailbox = new Mailbox(leadBox);
        List<Message> requests = leadMailbox.receive(true);
        assertEquals(1, requests.size());
        assertEquals(MessageType.APPROVAL_REQUEST, requests.get(0).type());

        // Lead 批准
        Mailbox.send(memberBox, new Message("lead",
            "APPROVED: 方案合理，注意配置超时时间", MessageType.APPROVAL_REPLY));

        // 成员收到批准
        Mailbox memberMailbox = new Mailbox(memberBox);
        List<Message> replies = memberMailbox.receive(true);
        assertEquals(1, replies.size());
        assertTrue(replies.get(0).body().startsWith("APPROVED"));

        // 再测驳回场景
        Mailbox.send(memberBox, new Message("lead",
            "REJECTED: 连接数过大，建议 10", MessageType.APPROVAL_REPLY));
        replies = memberMailbox.receive(true);
        assertEquals(2, replies.size());
        assertTrue(replies.get(1).body().startsWith("REJECTED"));

        System.out.println("✅ AC3: Approval flow — request → approve/reject");
    }

    // ===== AC4: Coordinator 双锁 =====
    @Test
    void ac4_coordinatorDoubleLock() {
        // 无环境变量 → 不激活
        assertFalse(CoordinatorMode.isActive());

        // 设置环境变量有点困难（会污染其他测试），验证工具过滤逻辑
        ToolRegistry original = new ToolRegistry();
        original.register(new ReadFileTool());
        original.register(new WriteFileTool());
        original.register(new EditFileTool());
        original.register(new ExecCommandTool());
        assertEquals(4, original.size());

        // 过滤
        ToolRegistry filtered = CoordinatorMode.filterTools(original);
        assertEquals(2, filtered.size());
        assertNotNull(filtered.get("read_file"), "read_file 应保留");
        assertNotNull(filtered.get("exec_command"), "exec_command 应保留");

        // write_file 和 edit_file 被移除
        try {
            filtered.get("write_file");
            fail("write_file 应被移除");
        } catch (IllegalArgumentException e) {
            // 预期
        }
        try {
            filtered.get("edit_file");
            fail("edit_file 应被移除");
        } catch (IllegalArgumentException e) {
            // 预期
        }

        System.out.println("✅ AC4: Coordinator mode filters write_file/edit_file");
    }

    // ===== AC5: 任务完成 → 状态流转 =====
    @Test
    void ac5_taskCompletionFlow() {
        // 生命周期：TODO → IN_PROGRESS → DONE
        SharedTask t = taskList.addTask("写单元测试", "覆盖核心逻辑", List.of(), "tester");
        assertEquals(TaskStatus.TODO, t.status());

        SharedTask updated = taskList.updateTask(t.id(), TaskStatus.IN_PROGRESS, null, null);
        assertEquals(TaskStatus.IN_PROGRESS, updated.status());

        updated = taskList.updateTask(t.id(), TaskStatus.DONE, null, null);
        assertEquals(TaskStatus.DONE, updated.status());

        // BLOCKED 状态
        SharedTask blocked = taskList.addTask("集成测试", "需要环境", List.of(), null);
        taskList.updateTask(blocked.id(), TaskStatus.BLOCKED, null, "环境未就绪");
        assertEquals(TaskStatus.BLOCKED, taskList.getTask(blocked.id()).status());

        System.out.println("✅ AC5: Task lifecycle — TODO→IN_PROGRESS→DONE / BLOCKED");
    }

    // ===== AC6: 上下文恢复（模拟） =====
    @Test
    void ac6_contextResumeWithoutRespawn() {
        TeamMember member = new TeamMember("worker", tempDir.resolve("work"), RuntimeBackend.IN_PROCESS, false);

        // 模拟第一次运行
        String session1 = java.util.UUID.randomUUID().toString().substring(0, 8);
        member.setSessionId(session1);
        member.setStatus(MemberStatus.IDLE);
        assertEquals(session1, member.sessionId());
        assertEquals(MemberStatus.IDLE, member.status());

        // Lead 指派新任务 → 可以从 session1 恢复上下文
        // （实际恢复依赖 AgentLoop 的会话持久化机制，这里验证 sessionId 不丢失）
        member.setStatus(MemberStatus.WORKING);
        assertEquals(session1, member.sessionId(), "sessionId 应在恢复时保持不变");

        // 模拟第二次任务完成
        member.setStatus(MemberStatus.IDLE);
        String session2 = java.util.UUID.randomUUID().toString().substring(0, 8);
        member.setSessionId(session2);
        assertNotEquals(session1, member.sessionId(), "新任务应有新 session");

        System.out.println("✅ AC6: Context resume — member idle → new task with session tracking");
    }

    // ===== 完整团队协作流程 =====
    @Test
    void fullTeamCollaborationFlow() throws Exception {
        // 1. 创建团队
        Team team = new Team("full-stack-team", "lead-role", tempDir);
        team.addMember(new TeamMember("frontend", tempDir.resolve("fe"), RuntimeBackend.IN_PROCESS, false));
        team.addMember(new TeamMember("backend", tempDir.resolve("be"), RuntimeBackend.IN_PROCESS, true));

        // 注册邮箱
        for (TeamMember m : team.members()) {
            Path box = mailboxesDir.resolve(m.name() + ".jsonl");
            Files.createFile(box);
            m.setMailboxPath(box);
            nameRegistry.register(m.name(), box);
        }
        Path leadBox = mailboxesDir.resolve("lead.jsonl");
        Files.createFile(leadBox);
        nameRegistry.register("lead", leadBox);

        // 2. 拆目标
        SharedTask analyze = taskList.addTask("分析需求", "分析用户故事", List.of(), "backend");
        SharedTask design = taskList.addTask("设计 API", "设计 REST 接口", List.of(analyze.id()), "backend");
        SharedTask implement = taskList.addTask("实现前端", "根据 API 实现页面", List.of(design.id()), "frontend");

        // 3. Backend 完成分析 → 发消息通知
        taskList.updateTask(analyze.id(), TaskStatus.DONE, null, null);
        Mailbox.send(nameRegistry.resolve("lead"),
            new Message("backend", "需求分析完成", MessageType.STATUS));

        // Lead 看到通知
        Mailbox leadMailbox = new Mailbox(leadBox);
        assertEquals(1, leadMailbox.receive(true).size());

        // 4. Backend 继续设计 API
        taskList.updateTask(design.id(), TaskStatus.IN_PROGRESS, null, null);
        assertEquals(0, taskList.readyTasks().size(),
            "design 在 IN_PROGRESS 中，implement 依赖 design 尚未完成，无就绪任务");

        // Frontend 需要审批
        TeamMember backendMember = team.getMember("backend");
        assertTrue(backendMember.requireApproval());

        // Backend 发审批请求
        Mailbox.send(leadBox, new Message("backend",
            "审批请求：API 设计使用 JWT 认证", MessageType.APPROVAL_REQUEST));

        // Lead 批准
        Mailbox.send(nameRegistry.resolve("backend"),
            new Message("lead", "APPROVED: JWT 方案可行", MessageType.APPROVAL_REPLY));

        // 5. 完成设计 → 前端开工
        taskList.updateTask(design.id(), TaskStatus.DONE, null, null);
        taskList.updateTask(implement.id(), TaskStatus.IN_PROGRESS, null, null);

        // 6. 前端完成
        taskList.updateTask(implement.id(), TaskStatus.DONE, null, null);
        Mailbox.send(leadBox, new Message("frontend", "前端实现完成", MessageType.STATUS));

        // 验证：所有任务完成
        assertEquals(0, taskList.listTasks(TaskStatus.TODO, null).size());
        assertEquals(0, taskList.listTasks(TaskStatus.IN_PROGRESS, null).size());
        assertEquals(3, taskList.listTasks(TaskStatus.DONE, null).size());

        // 验证：Lead 收到所有通知
        List<Message> leadMessages = leadMailbox.receive(false);
        assertTrue(leadMessages.size() >= 2, "Lead 应收到至少 2 条消息");

        System.out.println("✅ Full team collaboration: " +
            taskList.size() + " tasks, " + team.members().size() + " members, " +
            leadMessages.size() + " messages to Lead");
    }

    // ===== 任务列表条件过滤 =====
    @Test
    void taskListFiltering() {
        SharedTask t1 = taskList.addTask("A", "任务 A", List.of(), "alice");
        SharedTask t2 = taskList.addTask("B", "任务 B", List.of(), "bob");
        taskList.updateTask(t1.id(), TaskStatus.DONE, null, null);

        // 按状态过滤
        assertEquals(1, taskList.listTasks(TaskStatus.DONE, null).size());
        assertEquals(1, taskList.listTasks(TaskStatus.TODO, null).size());

        // 按指派过滤
        assertEquals(1, taskList.listTasks(null, "alice").size());
        assertEquals(1, taskList.listTasks(null, "bob").size());

        System.out.println("✅ Task list filtering by status + assignee");
    }
}
