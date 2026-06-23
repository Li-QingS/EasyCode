# Hook 引擎 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] `hook/` 包下 10 个源文件全部存在且编译通过（验证：`mvn compile -q`）
- [ ] `Main.java` 中加载 `HookConfig` 并构造 `HookEngine`（验证：启动日志含 Hook 加载信息）
- [ ] `AgentLoop.java` 中 6 个 `fire()` 调用点全部就位（验证：grep `hookEngine.fire` 计数 ≥6）
- [ ] `ToolExecutor.java` 中 PRE_TOOL 拦截点已插入（验证：grep `hookEngine.fire.*PRE_TOOL` 命中）

## 集成
- [ ] `HookEngine` 正确注入到 `AgentLoop` 和 `ToolExecutor`（验证：编译通过 + 构造链路完整）
- [ ] 所有 Hook 调用点不阻塞 Agent 主流程（验证：配置 1 条 `turn-start` 空动作规则，完成 3 轮对话，无延迟异常）

## 编译与测试
- [ ] `mvn compile -q` 无错误无警告
- [ ] `mvn test` 全部通过（含 T10/T11/T12 的 4 个新测试类）
- [ ] 无新增 lint 警告

## 端到端场景
- [ ] 场景 1：提示词注入 — 配置 `session-start → prompt(text="[INJECTED]")`，启动后 System Prompt 包含 `[INJECTED]`
- [ ] 场景 2：工具拦截 — 配置 `pre-tool → all(equals(name,exec_command), regex(command,rm\s+-rf))`，`rm -rf` 调用返回 `isError=true`
- [ ] 场景 3：配置校验 — 缺失 `event` 的规则导致启动拒绝，stderr 含 `event` 和行号
- [ ] 场景 4：故障隔离 — `post-llm-response → shell("exit 1")` 失败记日志，Agent 继续下一轮
- [ ] 场景 5：多事件顺序 — 三条规则的执行记录按 `turn-start → pre-tool → post-tool` 顺序
- [ ] 场景 6：once 语义 — `startup + once → shell` 只在首次启动执行
