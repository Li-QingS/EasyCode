# Hook 引擎 Plan

## 架构概览

新增 `com.easycode.hook` 包，五个核心组件：

```
hook/
├── HookEvent.java        — 事件枚举（10 种事件）
├── HookAction.java       — 动作接口 + 四种实现
├── HookRule.java         — 规则定义（event + if + action + once + async）
├── HookConfig.java       — YAML 加载 + 启动校验
└── HookEngine.java       — 调度引擎（事件触发 → 条件匹配 → 动作执行）
```

**调度流程：**
```
Agent 生命周期节点
    │
    ▼
HookEngine.fire(event, context)
    │
    ├─ 遍历 rules，筛选匹配 event 的规则
    ├─ 对每条规则：条件匹配（if 为空 → 无条件通过）
    │   ├─ 匹配成功：执行动作
    │   │   ├─ pre-tool + 拦截：返回 ToolResult(isError=true)
    │   │   ├─ async：提交到线程池
    │   │   └─ once：标记已执行，本次不再触发
    │   └─ 匹配失败：跳过，记录日志
    └─ 异常捕获：记录日志，不抛出
```

## 核心数据结构

### HookEvent（事件枚举）

```java
public enum HookEvent {
    STARTUP, SHUTDOWN,
    SESSION_START, SESSION_END,
    TURN_START, TURN_END,
    PRE_LLM_REQUEST, POST_LLM_RESPONSE,
    PRE_TOOL, POST_TOOL
}
```

### HookAction（动作接口）

```java
public interface HookAction {
    String execute(HookContext ctx) throws Exception;
    String type();
}
```

### HookContext

```java
public record HookContext(
    HookEvent event,
    Map<String, Object> vars
) {}
```

### 四种动作实现

| 动作 | 类 | 关键字段 |
|------|-----|---------|
| Shell | `ShellAction` | `command`, `cwd`, `env`, `timeout` |
| 提示词 | `PromptAction` | `text` |
| HTTP | `HttpAction` | `url`, `method`, `headers`, `body`, `timeout` |
| 子Agent | `SubAgentAction` | （占位，`execute()` 返回 `[sub-agent not yet implemented]`） |

### HookRule

```java
public record HookRule(
    String name,
    HookEvent event,
    ConditionNode condition,  // null = 无条件
    HookAction action,
    boolean once,
    boolean async
) {}
```

### ConditionNode（复用权限规则语法）

```java
public sealed interface ConditionNode {
    record Equals(String field, String value) implements ConditionNode {}
    record NotEquals(String field, String value) implements ConditionNode {}
    record Regex(String field, String pattern) implements ConditionNode {}
    record Glob(String field, String pattern) implements ConditionNode {}
    record All(List<ConditionNode> conditions) implements ConditionNode {}
    record Any(List<ConditionNode> conditions) implements ConditionNode {}
}
```

### HookEngine

```java
public class HookEngine {
    public Optional<ToolResult> fire(HookEvent event, Map<String, Object> vars);
    private boolean matches(ConditionNode condition, Map<String, Object> vars);
    private Optional<String> executeAction(HookRule rule, HookContext ctx);
}
```

## 模块设计

### HookConfig（规则加载与校验）

**职责：** 从 `easycode.hooks.yaml` 加载规则，启动时集中校验。

**校验项：** event 合法性、action.type 合法性、shell 必须有 command、http 必须有 url、pre-tool 禁止 async、all/any 子条件至少 1 条。

### HookEngine（调度引擎）

**职责：** 接收事件 → 筛选规则 → 逐条匹配条件 → 执行动作 → 收集拦截结果。

**AgentLoop 集成点：**

| 位置 | 事件 | 携带变量 |
|------|------|---------|
| Main.java 启动后 | STARTUP | {} |
| AgentLoop.run() 开始 | SESSION_START | {sessionId} |
| AgentLoop.run() 每轮开始 | TURN_START | {round} |
| 构造 Request 前 | PRE_LLM_REQUEST | {messageCount, estimatedTokens} |
| 收到响应后 | POST_LLM_RESPONSE | {text, toolCallCount} |
| ToolExecutor 权限检查前 | PRE_TOOL | {name, input} |
| ToolExecutor 执行后 | POST_TOOL | {name, success, contentLen} |
| AgentLoop.run() 结束 | SESSION_END | {totalRounds} |
| Main.java shutdown hook | SHUTDOWN | {} |

**PRE_TOOL 拦截流程：** ToolExecutor.executeOneTool() → HookEngine.fire(PRE_TOOL) → 匹配则返回 `ToolResult(isError=true, content=拒绝原因)` → AgentLoop 反馈给 LLM。

## 文件组织

```
src/main/java/com/easycode/
├── hook/
│   ├── HookEvent.java          — 事件枚举
│   ├── HookAction.java         — 动作接口
│   ├── ShellAction.java        — Shell 命令动作
│   ├── PromptAction.java       — 提示词注入动作
│   ├── HttpAction.java         — HTTP 请求动作
│   ├── SubAgentAction.java     — 子 Agent 占位动作
│   ├── HookRule.java           — 规则定义 record
│   ├── ConditionNode.java      — 条件表达式
│   ├── HookConfig.java         — YAML 加载 + 校验
│   ├── HookEngine.java         — 调度引擎
│   └── HookContext.java        — 上下文 record
├── agent/
│   ├── AgentLoop.java          — 修改：插入 fire() 调用点
│   └── ToolExecutor.java       — 修改：PRE_TOOL 拦截点
└── Main.java                   — 修改：加载 HookConfig，构造 HookEngine

src/test/java/com/easycode/hook/
├── HookConfigTest.java         — YAML 加载 + 校验测试
├── HookEngineTest.java         — 事件触发 + 匹配 + 拦截测试
├── ConditionMatchTest.java     — 四种匹配 + all/any 组合测试
└── HookActionTest.java         — Shell/Prompt/HTTP 动作测试
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 条件语法 | 复用 `ConditionNode` sealed interface | 避免重复造轮子 |
| YAML 解析 | SnakeYAML（已有依赖） | 不引入新库 |
| 拦截语义 | `PRE_TOOL` 返回 `ToolResult(isError=true)` | 统一拦截，模型收到错误后自然调整 |
| 异步执行 | `Executors.newCachedThreadPool()` | 轻量，pre-tool 强制同步 |
| `once` 实现 | `Set<String>` 内存标记 | spec 不做持久化 |
| 校验失败 | `throw new IllegalStateException` | 配置错误应立即暴露 |
| Hook 异常 | `try-catch` + `System.err` | 不中断主流程 |
| Shell 超时 | `Process.waitFor(timeout)` + `destroyForcibly()` | JDK 标准方式 |
| 提示词注入 | 通过 AgentLoop 上下文变量传递 | 简便，后续可扩展 |
