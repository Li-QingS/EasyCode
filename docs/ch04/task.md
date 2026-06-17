# Agent Loop Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/easycode/agent/AgentEvent.java` | 事件 sealed interface + 8 个 record |
| 新建 | `src/main/java/com/easycode/agent/StreamingCollector.java` | 双路收集器：实时转发 + 累积 |
| 新建 | `src/main/java/com/easycode/agent/ToolExecutor.java` | 分批并发执行，结果按序返回 |
| 新建 | `src/main/java/com/easycode/agent/AgentLoop.java` | ReAct 主循环编排、停止条件、历史维护 |
| 修改 | `src/main/java/com/easycode/tool/ToolRegistry.java` | 新增 `toToolsJson(Permission)` |
| 修改 | `src/main/java/com/easycode/conversation/ConversationMgr.java` | 新增便捷方法 |
| 修改 | `src/main/java/com/easycode/provider/OpenAIProvider.java` | 补齐工具调用完整支持 |
| 修改 | `src/main/java/com/easycode/tui/Tui.java` | 重构接入 AgentLoop + 事件消费 |
| 修改 | `src/main/java/com/easycode/Main.java` | 接入 AgentLoop |
| 新建 | `src/test/java/com/easycode/agent/ToolExecutorTest.java` | 并发安全、保序、超时测试 |
| 新建 | `src/test/java/com/easycode/agent/AgentLoopTest.java` | 停止条件、历史一致性测试 |

## T1: 创建 AgentEvent 事件族

**文件：** `src/main/java/com/easycode/agent/AgentEvent.java`
**依赖：** 无
**步骤：**
1. 定义 `public sealed interface AgentEvent`
2. 定义 8 个 permits：`TextDelta`, `ToolCallStart`, `ToolCallEnd`, `TokenUsage`, `IterationProgress`, `RoundComplete`, `Error`, `AgentFinished`
3. `TextDelta(String text)`：文本增量
4. `ToolCallStart(int index, String toolId, String toolName)`：工具调用开始
5. `ToolCallEnd(int index, String toolId, String toolName, ToolResult result)`：工具调用结束
6. `TokenUsage(int roundInput, int roundOutput, int totalInput, int totalOutput)`：Token 用量
7. `IterationProgress(int round, int maxRounds)`：迭代进度
8. `RoundComplete(int round)`：本轮结束
9. `Error(String message, boolean fatal)`：错误，fatal 表示是否致命
10. `AgentFinished(String finalText, int totalRounds, int totalInputTokens, int totalOutputTokens)`：循环结束

**验证：** `mvn -q compile` 通过

## T2: 新增 ToolRegistry.toToolsJson(Permission) 过滤方法

**文件：** `src/main/java/com/easycode/tool/ToolRegistry.java`
**依赖：** 无
**步骤：**
1. 新增 `public List<JsonNode> toToolsJson(Tool.Permission maxPermission)` — 只返回 `tool.permission().compareTo(maxPermission) <= 0` 的工具
2. 现有 `toToolsJson()` 委托调用 `toToolsJson(Permission.READ_WRITE)`（保持向后兼容）

**验证：** `mvn -q compile` 通过

## T3: ConversationMgr 新增便捷方法

**文件：** `src/main/java/com/easycode/conversation/ConversationMgr.java`
**依赖：** 无
**步骤：**
1. 新增 `public void addToolUse(ToolCall call)` — 构造含有 ToolUseBlock 的 ASSISTANT 消息并加入历史
2. 新增 `public void addToolResult(String toolUseId, String content, boolean isError)` — 构造含有 ToolResultBlock 的 USER 消息并加入历史

**验证：** `mvn -q compile` 通过；现有 `ConversationMgrTest` 仍通过

## T4: 创建 StreamingCollector

**文件：** `src/main/java/com/easycode/agent/StreamingCollector.java`
**依赖：** T1, T3
**步骤：**
1. 实现 `StreamHandler`，构造函数接收 `Consumer<AgentEvent> eventSink`
2. `onToken(text)`：`eventSink.accept(new TextDelta(text))` + `fullText.append(text)`
3. `onToolCall(call)`：`toolCalls.add(call)` （追加到末尾）
4. `onUsage(in, out)`：存为 `roundInputTokens` / `roundOutputTokens`，发 TokenUsage 事件
5. `onComplete()`：设置 `completed = true` 标志
6. `onError(e)`：转发 `eventSink.accept(new Error(e.getMessage(), true))`
7. 提供 getter：`getToolCalls()`, `getFullText()`, `getRoundInputTokens()`, `getRoundOutputTokens()`, `isCompleted()`, `hasError()`

**验证：** `mvn -q compile` 通过，无编译警告

## T5: 创建 ToolExecutor

**文件：** `src/main/java/com/easycode/agent/ToolExecutor.java`
**依赖：** T1, T2
**步骤：**
1. 创建 `public static List<ToolResult> executeAll(List<ToolCall> calls, ToolRegistry registry, Consumer<AgentEvent> eventSink, ObjectMapper mapper)`
2. 遍历 calls 分组逻辑：
   - 用 `registry.getPermission(name)` 判断：`READ_ONLY` -> 入当前只读批
   - 非只读 -> 先排空当前只读批（并发执行），再单独执行当前调用
   - 最后排空剩余只读批
3. 并发批执行：用 `ExecutorService`（cached thread pool），`invokeAll` 提交 Callable，每个包装 30s `Future.get(timeout)`
4. 超时返回 `ToolResult.err(name, "超时（>30s）")`
5. 每完成一个工具，发 `ToolCallEnd(index, id, name, result)` 事件
6. 结果按原始 index 排序存入 List，最终返回

**验证：** `mvn -q compile` 通过

## T6: OpenAIProvider 补齐工具调用

**文件：** `src/main/java/com/easycode/provider/OpenAIProvider.java`
**依赖：** 无（可并行）
**步骤：**
1. 修改 `chatStream` 方法注入 tools 参数 + 改为异步 HTTP（`sendAsync` + `BodyHandlers.fromLineSubscriber`），与 Anthropic 一致
2. `buildRequestBody` 接收 tools：
   - 注入 system 消息作为 `role: "system"` 首条
   - 若 tools 非空，构建 OpenAI 格式 tools 数组：`[{"type": "function", "function": {"name": ..., "description": ..., "parameters": ...}}]`
   - 添加 `"tool_choice": "auto"`
3. 历史序列化：解析 `MessageRecord.blocks()`，将 `ToolUseBlock` 转为 `role: "assistant"` + `tool_calls` 数组，`ToolResultBlock` 转为 `role: "tool"` + `tool_call_id` + `content`
4. SSE 解析新增：
   - `delta.tool_calls` 数组处理（累积 index -> id -> function.name -> function.arguments 增量）
   - arguments 完成后回调 `handler.onToolCall(new ToolCall(id, name, json.readTree(args)))`
   - `usage` 字段提取回调 `handler.onUsage`
5. 修改 `LlmProvider` 接口签名：`chatStream(history, tools, handler)`（接收 tools 参数）

**验证：** `mvn -q compile` 通过

## T7: 创建 AgentLoop 主循环

**文件：** `src/main/java/com/easycode/agent/AgentLoop.java`
**依赖：** T1-T6
**步骤：**
1. 定义内置常量：`MAX_ITERATIONS = 10`，`MAX_CONSECUTIVE_UNKNOWN = 3`
2. 构造函数：`AgentLoop(LlmProvider, ToolRegistry, ConversationMgr, Config)`
3. `run(userMessage, eventSink)`：
   - 将 userMessage 加入 conversation
   - 主循环 `for round = 1..MAX_ITERATIONS`：
     - 检查 `cancelled` -> 补取消结果，发 `AgentFinished(null)`，return null
     - 发 `IterationProgress(round, MAX)`
     - 构建 toolsJson（planMode 时过滤为 READ_ONLY）
     - 创建 StreamingCollector，调 `provider.chatStream`
     - 流出错 -> 发 Error，发 AgentFinished(null)，return null
     - 无工具调用 -> 发 AgentFinished(fullText)，return fullText
     - 检查工具是否都在 registry 中 -> 连续全部未知达阈值 -> 发 Error，return null
     - 发 ToolCallStart x N
     - 调 `ToolExecutor.executeAll`
     - 按序写 assistant + tool result 入 conversation
     - 发 RoundComplete
   - 触顶 -> 发 Error("达到迭代上限 (10 轮)")，发 AgentFinished(null)
4. `cancel()`：设置 `cancelled = true`
5. `setPlanMode(boolean)` / `isPlanMode()`
6. 未知工具时补 ToolResult("未知工具: " + name, isError=true) 并计数；已知工具重置计数器

**验证：** `mvn -q compile` 通过

## T8: 重构 Tui 接入 AgentLoop

**文件：** `src/main/java/com/easycode/tui/Tui.java`
**依赖：** T7
**步骤：**
1. 新增字段：`AgentLoop agentLoop`、`int totalInputTokens`、`int totalOutputTokens`
2. 构造函数接收 `AgentLoop`（由 Main 创建传入）
3. 新增命令：`/plan` -> `agentLoop.setPlanMode(true)` + 提示；`/do` -> `agentLoop.setPlanMode(false)` + 提示
4. `startStreamingChat()` 改为调用 `agentLoop.run(userMessage, this::handleEvent)`
5. 新增 `handleEvent(AgentEvent)` 分发：
   - `TextDelta` -> MarkdownRenderer 转发 print
   - `ToolCallStart` -> 记录，等待 ToolCallEnd
   - `ToolCallEnd` -> 打印工具行（名称 + 成功/失败 + 耗时 + 结果摘要，复用现有渲染逻辑）
   - `TokenUsage` -> 更新累计量，状态栏显示
   - `IterationProgress` -> 更新状态栏轮次
   - `RoundComplete` -> 打印分隔
   - `Error` -> 红色打印
   - `AgentFinished` -> 打印最终文本 + 计时
6. 保留 spinner、工具确认、MarkdownRenderer 逻辑
7. 移除旧的 `needFollowUp`、`doStreamingChat` 中的续答循环

**验证：** `mvn -q compile` 通过

## T9: Main 接入 AgentLoop

**文件：** `src/main/java/com/easycode/Main.java`
**依赖：** T7, T8
**步骤：**
1. 创建 `AgentLoop` 实例（传入 provider, registry, new ConversationMgr(), config）
2. 传给 `Tui` 构造函数

**验证：** `mvn -q compile` 通过

## T10: ToolExecutor 单元测试

**文件：** `src/test/java/com/easycode/agent/ToolExecutorTest.java`
**依赖：** T5
**步骤：**
1. 测试并发批内只读工具并行执行（用 sleep 验证总时间 < 串行之和）
2. 测试副作用工具串行执行
3. 测试混合批：只读 -> 只读（并发）-> 副作用（串行）-> 只读（新批并发）
4. 测试超时返回 err 结果
5. 测试结果按原始顺序返回
6. 测试 ToolCallEnd 事件按原始顺序发布

**验证：** `mvn test -pl . -Dtest=ToolExecutorTest` 通过

## T11: AgentLoop 单元测试

**文件：** `src/test/java/com/easycode/agent/AgentLoopTest.java`
**依赖：** T7
**步骤：**
1. 用 mock provider 模拟单轮纯文本响应 -> 验证返回 finalText，无工具执行
2. 模拟多轮工具调用 -> 验证循环推进、历史逐轮增长
3. 模拟迭代上限 -> 验证触顶停止、发 Error 事件
4. 模拟连续未知工具 -> 验证阈值停止
5. 模拟 provider 流出错 -> 验证停止但不崩溃
6. 模拟取消 -> 验证补结果、历史合法
7. 验证 planMode 下工具定义只含 READ_ONLY

**验证：** `mvn test -pl . -Dtest=AgentLoopTest` 通过

## 执行顺序

```
T1 -> T4 -> T5 -> T7 -> T8 -> T9
T2 ---+    +->      |
T3 ---+    |        |
T6 --------+        |
                    v
               T10, T11（测试）
```

- T1, T2, T3, T6 可并行
- T4 依赖 T1
- T5 依赖 T1, T2
- T7 依赖全部 T1-T6
- T8, T9 依赖 T7
- T10 依赖 T5；T11 依赖 T7
