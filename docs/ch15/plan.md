# Team Lead 系统 Plan

## 架构概览
新增 `team/` 包（含 `runtime/` 和 `lead/` 子包），18 个新文件 + 3 个修改文件。

核心模块：
- **Team + TeamMember** — 小组持久化对象
- **TeamPersistence** — 读写 `~/.easycode/teams/<name>/`
- **SharedTaskList** — 共享任务增删查改（依赖校验）
- **NameRegistry + Mailbox** — 点对点消息（锁文件并发）
- **TaskListTool + MailboxTool** — 协作工具（条件可见）
- **MemberRuntime + InProcessRuntime** — 成员执行后端
- **TeamLead** — 编排：拆目标→派生→合并
- **CoordinatorMode** — 双锁激活 + 工具裁剪

## 核心数据结构

### Team
- name, leadName, members, storageDir, taskList, nameRegistry

### TeamMember
- name, workDir, backend(IN_PROCESS/TERMINAL_WINDOW), requireApproval, status(IDLE/WORKING/WAITING_APPROVAL), mailboxPath, sessionId

### SharedTask
- id(UUID), title, description, status(TODO/IN_PROGRESS/DONE/BLOCKED), dependsOn(List), assignedTo, createdAt, updatedAt

### Message
- id(UUID), sender, body, type(TEXT/APPROVAL_REQUEST/APPROVAL_REPLY/STATUS/ASSIGNMENT), timestamp, read, summary

### 工具接口
- TaskListTool: add_task/list_tasks/get_task/update_task
- MailboxTool: send_message/read_mailbox/broadcast

## 存储布局
```
~/.easycode/teams/<team-name>/
├── team.json          ← Team 序列化
├── tasks.jsonl        ← SharedTask 每行 JSON
├── mailboxes/
│   ├── <member>.jsonl ← 邮箱
│   └── <member>.lock  ← 锁文件
```

## 模块交互
```
TeamLead.lead(userGoal)
  → 拆目标 → SharedTaskList.addTask() × N
  → 派生成员 → InProcessRuntime.start()
  → 成员协作（TaskListTool + MailboxTool）
  → 全部 IDLE → gitMerge(memberDirs)
```

## 技术决策
| 决策点 | 选择 | 理由 |
|--------|------|------|
| 协作工具可见性 | 条件 ToolRegistry | 不改 Tool 接口 |
| 消息寻址 | 名称注册表+邮箱文件 | 文件系统天然隔离 |
| 邮箱并发 | 锁文件+重试+过期 | 无外部依赖 |
| 上下文恢复 | 复用 SessionWriter JSONL | ch08 已有 |
| Coordinator 激活 | config+环境变量双锁 | 防误触发 |
| git merge | exec_command | 复用已有工具 |
