# Agent Loop Plan

## 架构概览

新增 `agent` 包，包含四个核心类；重构 `tui` 和 `provider` 以接入新架构。

```
用户输入 -> Tui -> AgentLoop.run() -> [ReAct 循环]
                    |                   |
                    |  发布 AgentEvent  | 调用 LlmProvider
                    |  给 Tui 消费      | 执行工具
                    v                   v
              Tui 渲染事件       ToolExecutor 分批并发
```

**AgentLoop** — 主循环编排器。接收用户输入后进入 ReAct 循环：每轮调 provider、收集响应、执行工具、回灌历史，直到模型不再请求工具或触发停止条件。通过 `Consumer<AgentEvent>` 向外发布事件。

**AgentEvent** — sealed interface 事件族。涵盖文本增量、工具调用开始/结束、Token 用量、迭代进度、本轮结束、错误、循环结束。事件是 Agent <-> 界面的唯一通道。

**StreamingCollector** — 实现 `StreamHandler`，走双路：实时转发 `onToken` -> `TextDelta` 事件；同时累积完整文本和工具调用列表，供 AgentLoop 判断本轮是否有工具请求。

**ToolExecutor** — 工具分批执行器。接收工具调用列表和 `ToolRegistry`，按只读/副作用分组：连续只读并发执行，有副作用串行执行，结果按原始顺序返回。每个工具受 30s 超时约束。

## 核心数据结构

### AgentEvent（sealed interface）

```java
public sealed interface AgentEvent {
    record TextDelta(String text) implements AgentEvent {}
    record ToolCallStart(int index, String toolId, String toolName) implements AgentEvent {}
    record ToolCallEnd(int index, String toolId, String toolName, ToolResult result) implements AgentEvent {}
    record TokenUsage(int roundInput, int roundOutput, int totalInput, int totalOutput) implements AgentEvent {}
    record IterationProgress(int round, int maxRounds) implements AgentEvent {}
    record RoundComplete(int round) implements AgentEvent {}
    record Error(String message, boolean fatal) implements AgentEvent {}
    record AgentFinished(String finalText, int totalRounds, int totalInputTokens, int totalOutputTokens) implements AgentEvent {}
}
```

### StreamingCollector（implements StreamHandler）

```
字段：
  - Consumer<AgentEvent> eventSink
  - StringBuilder fullText           // 累积全部文本
  - List<ToolCall> toolCalls         // 按出现顺序收集的完整工具调用

方法：
  - onToken(text)        -> 转发 TextDelta 事件 + 追加 fullText
  - onToolCall(call)     -> 追加 toolCalls
  - onUsage(in, out)     -> 存为 roundUsage
  - onComplete()         -> 标记完成
  - onError(e)           -> 转发 Error 事件

查询方法：
  - getToolCalls()       -> 不可变工具调用列表
  - getFullText()        -> 完整累积文本
  - getRoundInputTokens() / getRoundOutputTokens() -> 本轮用量
```

### ToolExecutor

```
静态方法：
  - executeAll(List<ToolCall>, ToolRegistry, Consumer<AgentEvent>)
        -> List<ToolResult>  // 按原始顺序返回

内部逻辑：
  1. 遍历 toolCalls，按 Tool.permission() 分组：
     - Permission.READ_ONLY -> 加入当前只读批
     - 否则 -> 先执行当前只读批（并发），再执行当前调用（串行）
  2. 并发批用 ExecutorService（cached thread pool）
  3. 每个工具执行包装 Future + 30s timeout
  4. 超时返回 ToolResult.err("超时")
  5. 每完成一个工具，发布 ToolCallEnd 事件
```

### ToolRegistry 新增方法

```java
List<JsonNode> toToolsJson(Tool.Permission maxPermission)
// 只返回 permission <= maxPermission 的工具定义
```

### MessageRecord 新增工厂方法

```java
static MessageRecord toolUse(String toolId, String toolName, JsonNode input)
static MessageRecord toolResult(String toolId, String content, boolean isError)
// 为了在取消/错误场景下快速构造合法的工具回合
```

## 模块设计

### AgentLoop

**职责：** ReAct 循环编排、停止条件判断、历史维护、用量累加

**对外接口：**
```java
public final class AgentLoop {
    public AgentLoop(LlmProvider provider, ToolRegistry tools, 
                     ConversationMgr conversation, Config config);
    
    // 运行循环，通过 eventSink 发布所有事件。返回最终答复文本（可为 null）。
    public String run(String userMessage, Consumer<AgentEvent> eventSink);
    
    // 取消当前运行
    public void cancel();
    
    // Plan Mode 切换
    public void setPlanMode(boolean planMode);
    public boolean isPlanMode();
}
```

**内部状态：**
- `volatile boolean cancelled`
- `boolean planMode`
- `int totalInputTokens`, `int totalOutputTokens`
- `int consecutiveUnknownTools` — 连续未知工具计数

**循环逻辑（run 方法内部）：**
```
1. 将 userMessage 加入 conversation
2. for round = 1 to MAX_ITERATIONS:
   a. if cancelled -> 补取消结果，发 AgentFinished(null)，return null
   b. 发布 IterationProgress(round, MAX_ITERATIONS)
   c. 构建 toolsJson（planMode 时只含 READ_ONLY）
   d. 创建 StreamingCollector
   e. 调用 provider.chatStream(history, toolsJson, collector)
   f. 若流出错 -> 发 Error，发 AgentFinished(null)，return null
   g. 若工具调用列表为空 -> 发 AgentFinished(fullText)，return fullText
   h. 检查连续未知工具 -> 达阈值则发 Error，return null
   i. 发布 ToolCallStart 事件 x N
   j. 调用 ToolExecutor.executeAll(toolCalls)
   k. 将工具调用消息 + 工具结果消息按序写入 conversation
   l. 发布 RoundComplete(round)
3. 达到迭代上限 -> 发 Error("达到迭代上限")，发 AgentFinished(null)，return null
```

**停止条件位置：**
- 自然完成 -> 2g
- 迭代上限 -> 步骤 3（循环结束后）
- 用户取消 -> 2a
- 连续未知工具 -> 2h
- 流出错 -> 2f

**历史一致性保证（F6）：**
- 取消时：遍历 toolCalls，对未执行的补 `ToolResult("已取消", isError=true)`
- 未知工具：补 `ToolResult("未知工具: " + name, isError=true)`
- 出错时：已执行的工具结果已回灌，未执行的补错误结果
- 确保 tool_use + tool_result 成对出现，role 交替

### Tui（重构）

**职责：** 命令解析（/plan, /do, /exit, /help）、启动 AgentLoop、消费事件并渲染

**改动要点：**
- 持有 `AgentLoop agentLoop` 实例（一个会话一个）
- `startStreamingChat()` 改为调用 `agentLoop.run(userMessage, this::handleEvent)`
- 新增 `handleEvent(AgentEvent)` 方法，按事件类型分发渲染
- 新增 `/plan`、`/do` 命令处理
- 保留 spinner、工具确认逻辑
- 维护累计用量和当前轮次状态，在状态栏展示

**渲染分发逻辑：**
```
TextDelta        -> 直接 print（经过 MarkdownRenderer）
ToolCallStart    -> 记录，等待 ToolCallEnd
ToolCallEnd      -> 打印工具行（名称 + 成功/失败 + 耗时 + 结果摘要）
TokenUsage       -> 更新状态栏用量
IterationProgress -> 更新状态栏轮次
RoundComplete    -> 分隔线
Error            -> 红色打印错误信息
AgentFinished    -> 打印最终答复 + 计时
```

### Provider 改动

**AnthropicProvider** — 无需改动，已完整支持工具调用解析。

**OpenAIProvider** — 需要补齐工具调用支持：
- `buildRequestBody` 中注入 tools 定义（OpenAI 格式：tools 数组，`type: "function"`）
- `buildRequestBody` 中序列化 tools 调用和 results（OpenAI 格式：`tool_calls` 数组 + `role: "tool"` 消息）
- SSE 解析中新增 `tool_calls` delta 处理（累积 id/name/arguments，完成后回调 `onToolCall`）
- 提取 `usage` 信息回调 `onUsage`
- 将 system prompt 作为第一条 `role: "system"` 消息注入

### ConversationMgr 改动

- 新增 `addToolUse(ToolCall)` / `addToolResult(String toolUseId, String content, boolean isError)` 便捷方法，封装 MessageRecord 构造

## 模块交互

```
Tui.startStreamingChat()
  |
  +- conversation.addUserMessage(line)
  |
  +- agentLoop.run(message, tui::handleEvent)
       |
       +- [每轮] provider.chatStream(history, tools, collector)
       |    |
       |    +- collector -> eventSink.accept(TextDelta / ToolUsage / Error)
       |
       +- [无工具调用] -> eventSink.accept(AgentFinished)
       |
       +- [有工具调用]
            |
            +- eventSink.accept(ToolCallStart x N)
            |
            +- ToolExecutor.executeAll(toolCalls, registry, eventSink)
            |    |
            |    +- [只读批] ExecutorService.invokeAll(parallel)
            |    +- [副作用] 逐个 execute()
            |    +- -> eventSink.accept(ToolCallEnd x N)
            |
            +- conversation.addMessage(toolUseMsg + toolResultMsg)
            |
            +- -> 下一轮
```

## 文件组织

```
src/main/java/com/easycode/
+-- agent/
|   +-- AgentLoop.java          — 主循环编排、停止条件
|   +-- AgentEvent.java         — 事件 sealed interface + records
|   +-- StreamingCollector.java — 双路收集器
|   +-- ToolExecutor.java       — 分批并发执行器
+-- provider/
|   +-- AnthropicProvider.java  — 无需改动
|   +-- OpenAIProvider.java     — 补齐工具调用支持（大改）
|   +-- ...
+-- tui/
|   +-- Tui.java                — 重构：接入 AgentLoop、事件消费
+-- tool/
|   +-- ToolRegistry.java       — 新增 toToolsJson(permission) 过滤方法
+-- conversation/
|   +-- ConversationMgr.java    — 新增便捷方法
+-- Main.java                   — 无结构性改动
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 事件传递机制 | `Consumer<AgentEvent>` 同步回调 | JLine 是单线程终端 UI，同步回调最简单；Agent <-> Tui 通过事件接口解耦，满足 F3 |
| 并发工具执行 | `Executors.newCachedThreadPool()` + `invokeAll` | 轻量，无额外依赖；失败隔离好（单工具超时不影响其他） |
| 迭代上限 | 内置常量 `MAX_ITERATIONS = 10` | 覆盖绝大多数多轮场景；值不大不小，防止失控 |
| 连续未知工具阈值 | `MAX_CONSECUTIVE_UNKNOWN = 3` | 允许偶发幻觉，连续 3 轮全未知则是系统性问题 |
| Plan Mode 实现 | AgentLoop 内部 flag + ToolRegistry 过滤 | 简单直接，不引入新类；模式切换即时生效 |
| Tui 重构范围 | 保留现有渲染逻辑，新增事件分发 | 最小化改动；MarkdownRenderer、spinner、工具确认逻辑复用 |
| OpenAI 工具格式 | 使用 `tools` 数组（`type: "function"`）+ `tool_choice: "auto"` | OpenAI Chat Completions API 标准格式；兼容所有 openai 兼容端点 |
| 取消实现 | `volatile boolean cancelled` + 检查点 | 简单可靠；在迭代边界和安全检查点检测 |
