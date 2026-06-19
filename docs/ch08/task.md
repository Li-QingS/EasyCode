# ch08 上下文管理 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `context/Constants.java` | 硬编码常量集合 |
| 新建 | `context/CompressEvent.java` | 压缩事件 record |
| 新建 | `context/SessionManager.java` | 会话 ID + 目录管理 |
| 新建 | `context/ReplacementLedger.java` | 替换决策账本（线程安全） |
| 新建 | `context/FileTracker.java` | 文件追踪状态（线程安全） |
| 改 | `context/TokenEstimator.java` | 升级到 chars/3.5 + 全量 usage 锚定 |
| 新建 | `context/Offloader.java` | F1+F2 联合落盘（替换旧 ToolResultTruncator） |
| 新建 | `context/RecoveryBuilder.java` | 恢复三段构造 |
| 重写 | `context/SummaryGenerator.java` | 9部分摘要 + PTL 重试 |
| 重写 | `context/ContextManager.java` | 三入口总调度 |
| 改 | `conversation/ConversationMgr.java` | 新增 assembleMessages() |
| 改 | `config/Config.java` | 增加 contextWindow + 默认值逻辑 |
| 改 | `agent/AgentLoop.java` | 接入三入口 + 紧急压缩 + 文件追踪 |
| 改 | `tui/Tui.java` | 命令注册表 + 系统消息 |
| 删 | `context/ToolResultTruncator.java` | 被 Offloader 替换 |
| 新建 | `context/ContextManagerTest.java` | 三入口 + 熔断 测试 |
| 新建 | `context/OffloaderTest.java` | F1/F2 落盘 + 预览体 测试 |
| 新建 | `context/ReplacementLedgerTest.java` | 决策冻结 + 并发 测试 |
| 新建 | `context/RecoveryBuilderTest.java` | 恢复三段 测试 |

## T1: 基础设施——常量、事件、会话

**文件：** `context/Constants.java`, `context/CompressEvent.java`, `context/SessionManager.java`
**依赖：** 无
**步骤：**
1. 创建 `Constants.java`，定义 F36 列出的所有 17 个硬编码常量（SINGLE_RESULT_THRESHOLD_BYTES=50000, MESSAGE_AGGREGATE_THRESHOLD_BYTES=200000, SUMMARY_OUTPUT_RESERVE=20000, AUTO_SAFETY_MARGIN=13000, MANUAL_SAFETY_MARGIN=3000, KEEP_RECENT_TOKEN_MIN=10000, KEEP_RECENT_MESSAGE_MIN=5, MAX_RECENT_FILES=5, FILE_SNAPSHOT_TOKEN_MAX=5000, AUTO_SUMMARY_CIRCUIT_BREAKER=3, PTL_DIRECT_RETRY_MAX=3, PTL_DROP_RATIO=0.2, ESTIMATE_CHARS_PER_TOKEN=3.5, PREVIEW_HEAD_BYTES_MAX=2048, PREVIEW_HEAD_LINES_MAX=20）
2. 创建 `CompressEvent.java`，record 含 reason(AUTO/MANUAL/EMERGENCY), estimatedBefore, estimatedAfter, replacedCount, success, errorMessage
3. 创建 `SessionManager.java`：`generateSessionId()` 返回 `"<unix_ts>-<6位随机hex>"`；`resolveToolResultDir(sessionId)` 返回 `.EasyCode/sessions/<id>/tool-results/`

**验证：** `mvn -q -DskipTests compile` 通过

## T2: 替换决策账本 + 文件追踪

**文件：** `context/ReplacementLedger.java`, `context/FileTracker.java`
**依赖：** T1
**步骤：**
1. 创建 `ReplacementLedger.java`：
   - 内部 `ReentrantLock lock` + `Set<String> seenIds` + `Map<String, String> replacements`
   - `decide(String id, String rawContent, Function<String, String> decisionFn)`：锁内原子操作，查 seenIds → 已见返回冻结值 → 未见图调 decisionFn → 写入 → 返回
   - `isSeen(id)` / `getReplacement(id)`：只读查询
   - `snapshot()`：返回账本只读快照供测试
2. 创建 `FileTracker.java`：
   - 内部 `synchronized` 保护的 `LinkedHashMap<String, FileSnapshot>`
   - `record(String path, String rawContent)`：记录当前时间戳
   - `recentSnapshots(int maxCount)`：返回按时间倒序的最近 N 个快照

**验证：** `mvn test -Dtest=ReplacementLedgerTest` 通过（决策冻结 + 并发写 + 落盘失败不写账本）

## T3: Token 估算器升级

**文件：** `context/TokenEstimator.java`
**依赖：** T1
**步骤：**
1. 常量 `CHARS_PER_TOKEN` 改为引用 `Constants.ESTIMATE_CHARS_PER_TOKEN`（3.5）
2. `updateAnchor(int inputTokens, int cacheReadTokens, int cacheCreationTokens, int outputTokens)`：锚点 = 四者之和
3. `estimate(ConversationMgr conv)` = max(增量 chars/3.5 + 锚点, 0)
4. 保留 `estimateNew()` 方法但不暴露为 public
5. 删除 `messageCharCount` 静态导出（改为内部使用）

**验证：** ContextManagerTest 中 TokenEstimator 用例通过

## T4: 第 1 层——Offloader（替换旧 ToolResultTruncator）

**文件：** `context/Offloader.java`
**依赖：** T1, T2, T3
**步骤：**
1. 创建 `Offloader.java`：
   - `offloadAndSnip(ConversationMgr conv, ReplacementLedger ledger, Path sessionDir)` 返回替换数量
   - 遍历 conv 历史，找到每条 RoleTool 消息的 toolResult 列表
   - F1：单条字节数 > 50000 → 落盘 → 构造预览体 → 调 ledger.decide()
   - F2：剩余项聚合 > 200000 → 按字节倒序依次落盘直到 ≤ 200000
   - 预览体（F4）：`[原始 N 字节]\n[前 M 行（≤20）或 前 N 字节（≤2048）择短]\n\n完整内容已保存至: <path>\n用 read_file 读取完整内容。`
   - 落盘失败：不调 ledger.decide()，id 不被 seen
   - 落盘 I/O 异步（用 daemon 线程写文件），不阻塞返回
2. 删除 `ToolResultTruncator.java`

**验证：** `mvn test -Dtest=OffloaderTest` 通过（F1单条落盘 + F2聚合落盘 + 预览体格式 + 幂等落盘 + 落盘失败降级）

## T5: 恢复段构造 + 第 2 层——摘要生成器

**文件：** `context/RecoveryBuilder.java`, `context/SummaryGenerator.java`
**依赖：** T1, T2, T3
**步骤：**
1. 创建 `RecoveryBuilder.java`：
   - `build(FileTracker tracker, List<JsonNode> tools)` 返回 `List<MessageRecord>`
   - 文件快照：取 `tracker.recentSnapshots(5)`，每文件超 5000 token 截断 + 标注
   - 工具列表：直接用传入的 `tools` 引用构造文本（与后续请求同一引用）
   - 边界提示：固定文案
2. 重写 `SummaryGenerator.java`：
   - `summarize(ConversationMgr conv, LlmProvider provider, ReplacementLedger ledger, FileTracker tracker, List<JsonNode> tools)` 返回成功/失败
   - 构建 prompt：9部分结构，`<analysis>` + `<summary>` 两阶段，不传 tools
   - 保留近期原文：双下界（token≥10000 且条数≥5），不拆分 tool_use/tool_result 对
   - 调 RecoveryBuilder 插入恢复三段
   - PTL 重试（F27）：捕获 prompt_too_long → 消息组丢弃策略（3次直接 + 20% 比例丢）
   - 替换历史：`conv.replaceAll(newHistory)`

**验证：** `mvn test -Dtest=RecoveryBuilderTest` 通过（文件快照截断 + 工具列表一致性 + 边界文案）

## T6: 总调度——ContextManager

**文件：** `context/ContextManager.java`
**依赖：** T1-T5
**步骤：**
1. 重写 `ContextManager.java`：
   - 构造：`ContextManager(LlmProvider provider, Config config, String sessionId)`
   - 内部持有：ReplacementLedger, FileTracker, TokenEstimator 轻量包装
   - `autoManage(conv, knownMsgCount, tools)`：Offloader → 估算 check → SummaryGenerator（熔断器拦截）
   - `manualCompact(conv, tools)`：跳过 Offloader/阈值/熔断，无条件 SummaryGenerator
   - `emergencyCompact(conv, tools)`：强制 Offloader → SummaryGenerator（与 manualCompact 共用核心）
   - 熔断：`consecutiveFailures >= 3` → 跳过自动摘要；任何一次成功 → 清零
   - `recordFileRead(path, rawContent)` → 转发给 FileTracker
   - `reset()` → 清空所有内部状态
   - 三入口用同一把 `ReentrantLock` 互斥（F37）

**验证：** `mvn test -Dtest=ContextManagerTest` 通过（自动触发 + 手动触发 + 紧急触发 + 熔断 + 阈值检查）

## T7: Config——contextWindow 字段

**文件：** `config/Config.java`
**依赖：** T1
**步骤：**
1. 新增 `private Integer contextWindow;`（可为 null）
2. 新增 `@JsonProperty("context_window")` 注解
3. `contextWindow()` getter：非 null 返回自身；null 时按 `protocol` 返回默认值（anthropic→200000, openai→128000）
4. 保留现有 `contextWindow(int)` setter

**验证：** `mvn test -Dtest=ConfigLoaderTest` 通过（新增用例：配置/未配置 context_window）

## T8: ConversationMgr——assembleMessages

**文件：** `conversation/ConversationMgr.java`
**依赖：** T2
**步骤：**
1. 新增 `assembleMessages(ReplacementLedger ledger)`：
   - 遍历当前 history，对每条消息的 toolResult blocks 调 `ledger.getReplacement(id)`
   - 返回替换后的 `List<MessageRecord>` 新列表（不修改原历史）
2. 保留现有 `replaceAll()` 方法（供 SummaryGenerator 使用）

**验证：** `mvn test -Dtest=ConversationMgrTest` 通过（新增用例：assembled 消息含替换文本）

## T9: AgentLoop——接入三入口

**文件：** `agent/AgentLoop.java`
**依赖：** T6, T7, T8
**步骤：**
1. 构造函数新增 `ContextManager contextManager` 字段，初始化为 `new ContextManager(provider, config, sessionId)`
2. 每轮 `run` 开始处：`CompressEvent evt = contextManager.autoManage(conversation, conversation.getHistory().size(), toolsJson)`；若 auto 触发了摘要则 emit 事件
3. 工具执行成功后：`if (tool.name().equals("read_file") && result.success()) contextManager.recordFileRead(path, rawContent)`
4. `provider.chatStream()` 捕获 `prompt_too_long` → `contextManager.emergencyCompact()` → 用新消息列表重试一次（`emergencyRetried` 标记）
5. `/compact` 路径：新增 `public CompressEvent forceCompact(List<JsonNode> tools)` 方法，内部调 `contextManager.manualCompact()`

**验证：** `mvn test -Dtest=AgentLoopTest` 通过（新增用例：紧急压缩 + 重试一次 + 不再二次重试）

## T10: Tui——命令注册表 + 系统消息

**文件：** `tui/Tui.java`
**依赖：** T6, T9
**步骤：**
1. 新增 `BUILTIN_COMMANDS`：Map<String, Runnable> 注册 `/exit`, `/plan`, `/do`, `/perm`, `/compact`, `/help`
2. `start()` 中检测输入以 `/` 开头 → 走命令路径，不发 LLM（F21）
3. `/compact` 命令：调 `agentLoop.forceCompact(tools.toToolsJson())`，显示压缩前后 token（F24）
4. 新增 `displaySystemMessage(String msg)` 方法，在 TUI 中展示系统消息
5. 自动压缩触发时显示 "正在压缩上下文..." + "已压缩，token 从 X 降至 Y"（F24a）
6. 紧急压缩触发时显示 "上下文撞墙，自动压缩中..."（F24b）

**验证：** `mvn test -Dtest=TuiTest`（如无则手动验证：启动后输入 `/compact` 不触发 LLM 对话请求）

## T11: 全量测试 + 清理

**文件：** 全部
**依赖：** T1-T10
**步骤：**
1. 删除 `context/ToolResultTruncator.java`
2. 更新 `easycode.yaml`，在注释中展示 `context_window` 字段（F32）
3. 运行 `mvn test` —— 原始用例 + 新增用例全部通过
4. `mvn -q -DskipTests package` 编译打包通过

**验证：** `mvn test` 全部通过，`mvn package` 成功

## 执行顺序

```
T1(常量/事件/会话) ─┬─→ T2(账本/追踪) ─┬─→ T4(Offloader) ─┐
                    │                   │                    │
                    ├─→ T3(Token升级)   │                    ├─→ T6(ContextManager) ─┬─→ T9(AgentLoop) ─→ T11
                    │                   │                    │                        │
                    └─→ T7(Config)      └─→ T5(RecoveryBuilder) ─┘              └─→ T10(Tui) ────→ T11
                                                + SummaryGenerator                    ↑
                                                                                    │
                                             T8(ConversationMgr) ────────────────────┘
```
