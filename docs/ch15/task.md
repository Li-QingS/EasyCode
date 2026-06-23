# Team Lead 系统 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `team/RuntimeBackend.java` | 枚举：IN_PROCESS, TERMINAL_WINDOW |
| 新建 | `team/TaskStatus.java` | 枚举：TODO, IN_PROGRESS, DONE, BLOCKED |
| 新建 | `team/MessageType.java` | 枚举：TEXT, APPROVAL_REQUEST, APPROVAL_REPLY, STATUS, ASSIGNMENT |
| 新建 | `team/MemberStatus.java` | 枚举：IDLE, WORKING, WAITING_APPROVAL |
| 新建 | `team/SharedTask.java` | 任务 record |
| 新建 | `team/Message.java` | 消息 record |
| 新建 | `team/TeamMember.java` | 成员定义 |
| 新建 | `team/Team.java` | 小组对象 |
| 新建 | `team/TeamPersistence.java` | 磁盘读写 |
| 新建 | `team/SharedTaskList.java` | 任务增删查改 |
| 新建 | `team/NameRegistry.java` | 名称→邮箱路径 |
| 新建 | `team/Mailbox.java` | 邮箱+锁并发 |
| 新建 | `team/TaskListTool.java` | 任务 Tool |
| 新建 | `team/MailboxTool.java` | 消息 Tool |
| 新建 | `team/runtime/MemberRuntime.java` | 运行时接口 |
| 新建 | `team/runtime/InProcessRuntime.java` | 同进程执行 |
| 新建 | `team/lead/TeamLead.java` | 编排 |
| 新建 | `team/lead/CoordinatorMode.java` | 双锁+裁剪 |
| 修改 | `subagent/RunAgentTool.java` | 团队派生 |
| 修改 | `agent/AgentLoop.java` | Coordinator 裁剪 |
| 修改 | `Main.java` | Team 初始化 |

## T1: 枚举和基础 Record
**文件:** RuntimeBackend.java, TaskStatus.java, MessageType.java, MemberStatus.java, SharedTask.java, Message.java
**依赖:** 无
**步骤:**
1. 4 个枚举文件
2. SharedTask record（构造补 UUID + 时间戳）
3. Message record（构造补 UUID + timestamp，默认 read=false，summary 取 body 前 100 字符）
**验证:** `mvn compile -q`

## T2: TeamMember + Team
**文件:** TeamMember.java, Team.java
**依赖:** T1
**步骤:**
1. TeamMember: name/workDir/backend/requireApproval/status/mailboxPath/sessionId
2. Team: name/leadName/members/storageDir/taskList/nameRegistry
**验证:** 编译通过

## T3: TeamPersistence
**文件:** TeamPersistence.java
**依赖:** T2
**步骤:**
1. save(Team) → team.json + tasks.jsonl + mailboxes/
2. load(name) → 反序列化 + 重建 taskList/registry
3. listTeams() / delete(name)
**验证:** 编译通过

## T4: NameRegistry + Mailbox
**文件:** NameRegistry.java, Mailbox.java
**依赖:** T1
**步骤:**
1. NameRegistry: Map<String,Path>, register/resolve
2. Mailbox: send/receive/markRead/broadcast + 锁文件（重试10次 100ms间隔 + 30s过期）
**验证:** 编译通过

## T5: SharedTaskList
**文件:** SharedTaskList.java
**依赖:** T1, T3
**步骤:**
1. 构造函数接收 tasks.jsonl 路径
2. addTask/listTasks/getTask/updateTask/readyTasks
3. dependsOn 校验
**验证:** 编译通过

## T6: TaskListTool + MailboxTool
**文件:** TaskListTool.java, MailboxTool.java
**依赖:** T4, T5
**步骤:**
1. TaskListTool 实现 Tool，委托 SharedTaskList
2. MailboxTool 实现 Tool，委托 Mailbox
**验证:** 编译通过

## T7: 运行后端
**文件:** runtime/MemberRuntime.java, runtime/InProcessRuntime.java
**依赖:** T2, T6
**步骤:**
1. MemberRuntime 接口: start/stop/isRunning/getStatus/getSessionId
2. InProcessRuntime: 创建独立 AgentLoop + 注入协作工具
**验证:** 编译通过

## T8: TeamLead + CoordinatorMode
**文件:** lead/TeamLead.java, lead/CoordinatorMode.java
**依赖:** T3, T7
**步骤:**
1. TeamLead.lead() → gitMerge()
2. CoordinatorMode.isActive() + filterTools()
**验证:** 编译通过

## T9: RunAgentTool 改造
**文件:** subagent/RunAgentTool.java
**依赖:** T8
**步骤:**
1. 支持传入 SharedTaskList + Mailbox
2. 注入 TaskListTool + MailboxTool 到子 Agent
**验证:** 编译通过 + 现有测试通过

## T10: Main + AgentLoop 集成
**文件:** Main.java, agent/AgentLoop.java
**依赖:** T8, T9
**步骤:**
1. Main 加载 Team + 注册 TeamLead
2. AgentLoop Coordinator 工具裁剪
**验证:** `mvn compile -q` + `mvn test` 全量通过

## 执行顺序
```
T1 → T2 → T3 → T5 → T6 → T7 → T8 → T9 → T10
       ↘                    ↗
         T4 ────────────────┘
```
