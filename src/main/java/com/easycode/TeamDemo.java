package com.easycode;

import com.easycode.team.*;
import java.nio.file.*;
import java.util.List;

/** 手动测试 Team Lead 完整流程 */
public class TeamDemo {
    public static void main(String[] args) throws Exception {
        Path demoDir = Path.of(".easycode/team-demo");
        Files.createDirectories(demoDir);
        Path mailboxesDir = demoDir.resolve("mailboxes");
        Files.createDirectories(mailboxesDir);

        // 1. 创建小组
        System.out.println("=" .repeat(50));
        System.out.println("   Team Lead 功能演示");
        System.out.println("=" .repeat(50));

        Team team = new Team("dev-team", "lead", demoDir);
        team.addMember(new TeamMember("frontend", demoDir.resolve("fe"), RuntimeBackend.IN_PROCESS, false));
        team.addMember(new TeamMember("backend", demoDir.resolve("be"), RuntimeBackend.IN_PROCESS, true));
        System.out.println("\n[1] 创建小组: " + team.name());
        System.out.println("    成员: " + team.members().stream().map(TeamMember::name).toList());

        // 2. 共享任务列表
        Path tasksFile = demoDir.resolve("tasks.jsonl");
        Files.createFile(tasksFile);
        SharedTaskList taskList = new SharedTaskList(tasksFile);

        SharedTask t1 = taskList.addTask("设计数据库", "设计用户表和权限表", List.of(), "backend");
        SharedTask t2 = taskList.addTask("实现 DAO", "编写数据访问层", List.of(t1.id()), "backend");
        SharedTask t3 = taskList.addTask("实现前端页面", "编写登录和注册页面", List.of(t2.id()), "frontend");

        System.out.println("\n[2] 拆解目标为 3 个任务:");
        for (SharedTask t : taskList.all()) {
            System.out.printf("    [%s] %-16s 指派:%-10s 依赖:%s%n",
                t.status(), t.title(), t.assignedTo() != null ? t.assignedTo() : "无",
                t.dependsOn().isEmpty() ? "无" : t.dependsOn());
        }

        System.out.println("\n[3] 就绪任务: " + taskList.readyTasks().size() + " 个");
        taskList.readyTasks().forEach(t -> System.out.println("    → " + t.title()));

        // 3. 消息系统
        NameRegistry registry = new NameRegistry();
        Path leadBox = mailboxesDir.resolve("lead.jsonl");
        Path feBox = mailboxesDir.resolve("frontend.jsonl");
        Path beBox = mailboxesDir.resolve("backend.jsonl");
        Files.createFile(leadBox);
        Files.createFile(feBox);
        Files.createFile(beBox);
        registry.register("lead", leadBox);
        registry.register("frontend", feBox);
        registry.register("backend", beBox);

        System.out.println("\n[4] 消息系统就绪，注册表: " + registry.size() + " 个成员");

        // 4. 模拟协作流程
        System.out.println("\n[5] ===== 模拟协作流程 =====");

        // Backend 完成设计
        taskList.updateTask(t1.id(), TaskStatus.DONE, null, null);
        Mailbox.send(leadBox, new Message("backend", "数据库设计完成，3 张表", MessageType.STATUS));
        System.out.println("  backend → lead: 数据库设计完成");

        // Backend 请求审批
        Mailbox.send(leadBox, new Message("backend", "DAO 层使用 JdbcTemplate 方案", MessageType.APPROVAL_REQUEST));
        System.out.println("  backend → lead: [审批请求] DAO 层方案");

        // Lead 批准
        Mailbox.send(beBox, new Message("lead", "APPROVED: JdbcTemplate 方案可行", MessageType.APPROVAL_REPLY));
        System.out.println("  lead → backend: [批准]");

        // Backend 完成 DAO
        taskList.updateTask(t2.id(), TaskStatus.DONE, null, null);
        System.out.println("  backend: DAO 完成");

        // Frontend 领任务开工
        taskList.updateTask(t3.id(), TaskStatus.IN_PROGRESS, null, null);
        Mailbox.send(feBox, new Message("lead", "前端页面开工，有问题随时沟通", MessageType.ASSIGNMENT));
        System.out.println("  lead → frontend: 前端开工");

        // Frontend 发消息给 Backend 问接口细节
        Mailbox.send(beBox, new Message("frontend", "登录接口返回格式是 JSON 吗？", MessageType.TEXT));
        System.out.println("  frontend → backend: 确认接口格式");

        // Backend 回复
        Mailbox.send(feBox, new Message("backend", "{code:0, data:{token:xxx}}", MessageType.TEXT));
        System.out.println("  backend → frontend: 接口格式确认");

        // Frontend 完成
        taskList.updateTask(t3.id(), TaskStatus.DONE, null, null);
        Mailbox.send(leadBox, new Message("frontend", "前端页面完成", MessageType.STATUS));
        System.out.println("  frontend → lead: 前端完成");

        // 5. 汇总
        System.out.println("\n[6] ===== 汇总 =====");
        System.out.println("  任务状态: " + taskList.listTasks(TaskStatus.DONE, null).size() + "/" + taskList.size() + " 完成");

        // Lead 邮箱
        Mailbox leadMailbox = new Mailbox(leadBox);
        List<Message> leadMsgs = leadMailbox.receive(false);
        System.out.println("  Lead 收到 " + leadMsgs.size() + " 条消息:");
        for (Message m : leadMsgs) {
            System.out.printf("    [%s] %s <%s> %s%n",
                m.read() ? "✓" : "●", m.sender(), m.type(), m.summary());
        }

        // Backend 邮箱
        Mailbox beMailbox = new Mailbox(beBox);
        List<Message> beMsgs = beMailbox.receive(false);
        System.out.println("  Backend 收到 " + beMsgs.size() + " 条消息:");
        for (Message m : beMsgs) {
            System.out.printf("    [%s] %s <%s> %s%n",
                m.read() ? "✓" : "●", m.sender(), m.type(), m.summary());
        }

        System.out.println("\n" + "=" .repeat(50));
        System.out.println("   演示完毕！所有任务完成，消息流转正常");
        System.out.println("=" .repeat(50));

        // 清理
        deleteRecursive(demoDir);
    }

    private static void deleteRecursive(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
