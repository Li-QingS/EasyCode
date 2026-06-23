# 子 Agent 系统 Plan

## 架构概览

新增 `com.easycode.subagent` 包 + 修改 `tool` 包：

```
subagent/
├── AgentDef.java           — Agent 定义 record
├── AgentDefLoader.java     — 三来源加载（builtin → user → project）
├── RunAgentTool.java       — 统一 run_agent 工具
├── SubAgent.java           — 子 Agent 运行器（Callable<TaskRecord>）
├── SubAgentContext.java    — 运行时上下文
├── TaskManager.java        — 后台任务管理器
└── TaskRecord.java         — 任务记录
```

**调度流程：** run_agent(mode, name, prompt) → mode 分流 → filteredTools → SubAgent → TaskManager → 同步/异步回调。

**共享/隔离：** LlmProvider/HookEngine/文件系统 共享；ConversationMgr/PermissionMode/token计数/ContextManager 隔离。

## 核心数据结构

### AgentDef
```java
public record AgentDef(String name, String description, String systemPrompt,
    List<String> toolsAllow, List<String> toolsDeny, String model,
    int maxTurns, String permission) {}
```

### AgentDefLoader
```java
public class AgentDefLoader {
    public static Map<String, AgentDef> loadAll(Path projectDir);  // builtin→user→proj 同名覆盖
    private static AgentDef parse(Path file);                       // YAML frontmatter + 正文
}
```

### RunAgentTool
实现 `Tool` 接口，参数 `mode(defined|fork)`/`name`/`prompt`/`background`，分流到 SubAgent。

### SubAgent
```java
public class SubAgent implements Callable<TaskRecord> {
    // 独立 ConversationMgr + 内部 AgentLoop + 跑到底执行
    @Override public TaskRecord call() { ... }
}
```

### TaskRecord
```java
public enum TaskStatus { PENDING, RUNNING, DONE, ERROR, TIMEOUT }
public record TaskRecord(String id, String agentName, TaskStatus status,
    String output, int turnsUsed, int inputTokens, int outputTokens,
    long startTimeMs, long endTimeMs) {}
```

### TaskManager
```java
public class TaskManager {
    public String submit(SubAgent, boolean background);
    public TaskRecord await(String taskId, long timeoutSec);
    public Optional<TaskRecord> get(String taskId);
}
```

## 模块设计

### AgentDefLoader — 启动时一次性加载，三来源同名覆盖
### RunAgentTool — 模式分流 + 工具过滤 + 创建 SubAgent + 提交 TaskManager
### SubAgent — Callable 实现，独立上下文，内部 AgentLoop 跑到底
### TaskManager — 线程池管理，状态追踪，PENDING→RUNNING→DONE/ERROR/TIMEOUT

## 文件组织

```
src/main/java/com/easycode/
├── subagent/ (7 files)    — 新增
├── tool/ToolRegistry.java — 修改：注册 RunAgentTool
├── agent/AgentLoop.java   — 修改：暴露内部构造
└── Main.java              — 修改：初始化 AgentDefLoader + TaskManager

src/main/resources/builtin/agents/ — 内置 Agent 定义
src/test/java/com/easycode/subagent/ — 4 个测试类
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| SubAgent 实现 | `Callable<TaskRecord>` | 适配 ExecutorService，同步/异步统一 |
| 工具过滤 | filteredTools 局部构建 | 全局 Registry 稳定，作用域限定 |
| 嵌套禁止 | 硬编码排除 run_agent | 简单可靠 |
| AgentDef 缓存 | 启动时 Map 缓存 | 避免重复 IO |
| Fork cache | 系统提示追加 + 对话完整复制 | 前缀一致，KV-cache 可命中 |
| 线程池 | FixedThreadPool(CPU核数) | 控制并发 |
| 超时 | Future.get(timeout) + 不 cancel | 子 Agent 继续跑 |
| AgentLoop 复用 | 共享组件，独立实例 | 消息隔离 |
| permission | 字符串→PermissionMode | 与现有权限对接 |
