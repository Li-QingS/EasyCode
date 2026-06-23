# Team Lead 系统 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] `team/` 包下 18 个文件全部存在且编译通过（验证：`mvn compile -q`）
- [ ] `SharedTask` record 含 id/title/description/status/dependsOn/assignedTo/createdAt/updatedAt（验证：grep 字段名）
- [ ] `Message` record 含 id/sender/body/type/timestamp/read/summary，落盘时自动补 timestamp 和未读状态（验证：单元测试构造 Message，timestamp 非 0，read=false）
- [ ] `TeamPersistence.save()` 在 `~/.easycode/teams/<name>/` 下创建 team.json + tasks.jsonl + mailboxes/（验证：运行后检查目录结构）
- [ ] `Mailbox.send()` 追加 JSON 行到接收方邮箱文件（验证：send 后 cat 邮箱文件含消息）
- [ ] `SharedTaskList.addTask()` 校验 dependsOn 存在性（验证：引用不存在的 taskId 抛异常）
- [ ] `SharedTaskList.readyTasks()` 返回依赖全部 DONE 的 TODO 任务（验证：构造 3 个任务 A→B→C，验证 readyTasks 仅返回 A）
- [ ] `InProcessRuntime.start()` 创建独立 AgentLoop 并提交到线程池（验证：编译通过 + 启动无异常）
- [ ] `TeamLead.lead()` 调用后拆出任务并派生成员（验证：编译通过）
- [ ] `CoordinatorMode.isActive()` 双锁激活（验证：config=true+env=1 为 true，其余组合 false）
- [ ] `CoordinatorMode.filterTools()` 移除 write_file/edit_file（验证：过滤后 ToolRegistry 不含这两个工具）
- [ ] `RunAgentTool` 支持传入 SharedTaskList/Mailbox 参数（验证：编译通过）
- [ ] `Main.java` 启动时加载 Team 并清理过期 Worktree（验证：启动日志无异常）

## 集成
- [ ] TaskListTool 和 MailboxTool 仅在 Team 成员 ToolRegistry 中注册，主 Agent 和普通子 Agent 不可见（验证：检查 ToolRegistry 注册逻辑）
- [ ] Coordinator 模式下 AgentLoop 的 toolsJson 不含 write_file/edit_file（验证：`mvn test` 断言）
- [ ] 成员需审批时走：发 APPROVAL_REQUEST → Lead 收 → Lead 发 APPROVAL_REPLY → 成员检查后执行/重规划（验证：端到端场景）
- [ ] 成员完成后状态变为 IDLE，发 STATUS 消息给 Lead（验证：InProcessRuntime 退出后检查 status 和消息）

## 编译与测试
- [ ] `mvn compile -q` 无错误无警告
- [ ] `mvn test` 全部通过（含新增 Team 相关测试）
- [ ] 现有 122 个测试（不含已有 HookActionTest 网络问题）全部通过

## 端到端场景
- [ ] **AC1：创建小组 → 拆目标 → 派生** — TeamLead 将 "实现用户登录" 拆为 3 个任务（写 Model → 写 DAO → 写 Controller），派生 2 个队员，共享任务列表可查
- [ ] **AC2：队员间点对点消息** — 队员 A 完成任务后发消息给队员 B，B 能从邮箱读到消息并基于此继续
- [ ] **AC3：审批流** — 需审批队员发 APPROVAL_REQUEST → Lead 批准 → 队员执行；Lead 驳回 → 队员重新规划
- [ ] **AC4：Coordinator 双锁** — config=false 时设置 env=1 不生效；config=true+env=1 生效后 write_file 不可用
- [ ] **AC5：git merge** — 两队员分别改了不同文件 → Lead merge 无冲突自动合并；两队员改了同一文件 → 回报冲突
- [ ] **AC6：上下文恢复** — 队员完成任务标记 IDLE → Lead 发消息指派新任务 → 队员从磁盘恢复上下文继续，不重新 spawn
- [ ] **AC7：邮箱并发** — 两队员同时写同一邮箱 → 锁保护，一个成功一个重试后成功，消息不丢失不损坏
