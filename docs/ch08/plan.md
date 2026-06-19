# ch08 上下文管理 Plan

## 架构概览

```
context 包（重写，8 个新/改文件）
├── ContextManager      ── 总调度：两层压缩串联 + 紧急/手动路径
├── ReplacementLedger   ── 替换决策账本（seenIds + id→预览映射，线程安全）
├── Offloader           ── 第 1 层：单条+单轮聚合落盘（原 ToolResultTruncator 重写）
├── SummaryGenerator    ── 第 2 层：LLM 摘要（9部分结构 + PTL 重试）
├── RecoveryBuilder     ── 恢复三段构造（文件快照 + 工具列表 + 边界提示）
├── FileTracker         ── 文件追踪状态（最近 5 个文件，线程安全）
├── SessionManager      ── 会话 ID + 目录管理
├── TokenEstimator      ── 估算器（升级到 chars/3.5）
├── Constants           ── 硬编码常量集合
└── CompressEvent       ── 压缩事件（原因/前后 token/替换数）

agent 包（改造）
└── AgentLoop           ── 接入紧急压缩 + 文件追踪 + /compact 路径

tui 包（改造）
└── Tui                 ── 命令注册表（/exit /plan /do /compact /perm）+ 系统消息展示

conversation 包（微改）
└── ConversationMgr     ── 新增 assembleMessages() 结合账本做替换
```

核心数据流：

```
Agent 每轮 run 开始
  │
  ├─→ ContextManager.autoManage()
  │     ├─→ Offloader.offloadAndSnip(conv, ledger)   ← 第 1 层
  │     └─→ 估算 token > 阈值？
  │           └─→ SummaryGenerator.summarize(conv, provider, ledger)
  │                 ├─→ 生成摘要（带 PTL 重试）
  │                 └─→ RecoveryBuilder.build(tracker, tools)
  │                       └─→ 插入恢复三段
  │
  ├─→ ConversationMgr.assembleMessages(ledger)  ← 用冻结的替换串组装请求
  │
  └─→ provider.chatStream(...)
        │
        ├─ 成功 → TokenEstimator.updateAnchor(usage)
        └─ prompt_too_long → ContextManager.emergencyCompact()
                               └─→ 强制第 1 层 + 摘要 + 重试一次
```

依赖方向（无环）：`context → conversation, provider`；`agent → context`；`tui → context, agent`

---

## 核心数据结构

### ReplacementLedger

```java
public final class ReplacementLedger {
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> seenIds = new HashSet<>();       // 已决策的 toolUseId
    private final Map<String, String> replacements = new HashMap<>(); // id→预览字符串

    /** 原子决策：给定 toolUseId 和原始 content，返回该用的字符串（原文或预览）。
        若已决策过则直接返回冻结值；否则走决策逻辑并写入账本。 */
    public String decide(String toolUseId, String rawContent, DecisionFn fn);

    /** 查询已决策的替换文本（不修改状态） */
    public String getReplacement(String toolUseId);

    public boolean isSeen(String toolUseId);
}
```

### Offloader

```java
public final class Offloader {
    /** 对 conversation 中所有 RoleTool 消息执行 F1+F2 联合扫描。
        返回被替换的 toolUseId 数量。 */
    public int offloadAndSnip(ConversationMgr conv, ReplacementLedger ledger, Path sessionDir);
}
```

### FileTracker

```java
public final class FileTracker {
    /** 记录一次成功的文件读取 */
    public void record(String path, String rawContent, long timestamp);

    /** 获取最近 N 个文件快照，按时间倒序 */
    public List<FileSnapshot> recentSnapshots(int maxCount);
}

public record FileSnapshot(String path, long readTime, String content) {}
```

### CompressEvent

```java
/** 发给 TUI 的压缩事件 */
public record CompressEvent(
    CompressReason reason,   // AUTO / MANUAL / EMERGENCY
    long estimatedBefore,
    long estimatedAfter,
    int replacedCount,
    boolean success,
    String errorMessage
) {}

public enum CompressReason { AUTO, MANUAL, EMERGENCY }
```

### SessionManager

```java
public final class SessionManager {
    public static String generateSessionId();  // "<unix_ts>-<short_random>"
    public static Path resolveToolResultDir(String sessionId);
}
```

### Constants

```java
public final class Constants {
    public static final int SINGLE_RESULT_THRESHOLD_BYTES = 50_000;
    public static final int MESSAGE_AGGREGATE_THRESHOLD_BYTES = 200_000;
    public static final int SUMMARY_OUTPUT_RESERVE = 20_000;
    public static final int AUTO_SAFETY_MARGIN = 13_000;
    public static final int MANUAL_SAFETY_MARGIN = 3_000;
    public static final int KEEP_RECENT_TOKEN_MIN = 10_000;
    public static final int KEEP_RECENT_MESSAGE_MIN = 5;
    public static final int MAX_RECENT_FILES = 5;
    public static final int FILE_SNAPSHOT_TOKEN_MAX = 5_000;
    public static final int AUTO_SUMMARY_CIRCUIT_BREAKER = 3;
    public static final int PTL_DIRECT_RETRY_MAX = 3;
    public static final double PTL_DROP_RATIO = 0.2;
    public static final double ESTIMATE_CHARS_PER_TOKEN = 3.5;
    public static final int PREVIEW_HEAD_BYTES_MAX = 2048;
    public static final int PREVIEW_HEAD_LINES_MAX = 20;
}
```

---

## 模块设计

### ContextManager

**职责：** 调度两层压缩，提供三个入口（自动 / 手动 / 紧急），管理熔断状态。

**对外接口：**
```java
public final class ContextManager {
    public ContextManager(LlmProvider provider, Config config, String sessionId);
    public CompressEvent autoManage(ConversationMgr conv, int knownMsgCount, List<JsonNode> tools);
    public CompressEvent manualCompact(ConversationMgr conv, List<JsonNode> tools);
    public CompressEvent emergencyCompact(ConversationMgr conv, List<JsonNode> tools);
    public void recordFileRead(String path, String rawContent);
    public void reset();
}
```

**关键点：**
- `autoManage`: 先调 Offloader（第 1 层），再判断是否需要摘要（第 2 层）。熔断器拦截自动摘要。返回 CompressEvent。
- `manualCompact`: 跳过第 1 层、跳过阈值、跳过熔断。无条件触发摘要。
- `emergencyCompact`: 先强制第 1 层，再调摘要（与 manualCompact 共用核心路径）。
- 持有 ReplacementLedger、FileTracker、TokenEstimator 的内部实例。
- 三个入口都必须线程安全——用 ReentrantLock 保护。

### ReplacementLedger

**职责：** 管理替换决策的冻结账本。同一个 toolUseId 只决策一次，后续返回冻结值。

**关键点：**
- `decide()` 在锁内原子完成：查 seenIds → 已见则返回冻结值 → 未见图走决策函数 → 写入账本 → 返回。
- 决策函数返回 `null` 表示"不替换"，此时只写 seenIds 不写 replacements。
- 决策函数抛异常时，不写 seenIds、不写 replacements，保证账本干净。

### Offloader

**职责：** 执行 F1（单条超 50000 字节落盘）+ F2（单轮聚合超 200000 字节落盘）。

**关键点：**
- 遍历 conv.getHistory()，找每条 RoleTool 消息的所有 toolResult blocks。
- 按字节倒序排序候选列表。
- F1 先处理：单条超阈值 → 落盘 → 构造预览体 → 调 ledger.decide() 写入替换。
- F2 再处理：剩余项聚合超阈值 → 按排序依次落盘直到聚合 ≤ 200000。
- 预览体格式（F4）：`[原始 N 字节]\n[前 M 行/字节预览]\n\n完整内容已保存至: <path>\n用 read_file 读取完整内容。`
- 落盘失败 → 不在账本中记录该 id，下次重新尝试。

### SummaryGenerator

**职责：** 生成 9 部分结构化摘要，支持 PTL 重试策略。

**对外接口：**
```java
public final class SummaryGenerator {
    /**
     * @return true 摘要成功，false 失败
     */
    public boolean summarize(ConversationMgr conv, LlmProvider provider,
            ReplacementLedger ledger, FileTracker tracker, List<JsonNode> tools);
}
```

**关键点：**
- 构建摘要 prompt（F8-F10）：不传 tools，`<analysis>` + `<summary>` 两阶段，9 部分结构。
- 保留近期原文（F11-F12）：双下界（token≥10000 且条数≥5），不拆分 tool_use/tool_result 对。
- PTL 重试（F27）：摘要请求撞 PTL 时，按消息组分组的丢弃策略重试。
- 摘要完成后调 RecoveryBuilder 插入恢复三段。

### RecoveryBuilder

**职责：** 构造摘要后的恢复三段（文件快照 + 工具列表 + 边界提示）。

**对外接口：**
```java
public final class RecoveryBuilder {
    public List<MessageRecord> build(FileTracker tracker, List<JsonNode> tools);
}
```

**关键点：**
- 文件快照（F16）：最多 5 个，超 5000 token 截断并标注 `(content truncated)`。
- 工具列表（F17）：与本次请求的 tools 参数**同一引用**（引用相等），不做独立重算。
- 边界提示（F18）：固定文案。

### FileTracker

**职责：** 追踪最近读取的文件，为恢复段提供快照数据。

**关键点：**
- `record()` 写入用 `synchronized` 保护。
- `recentSnapshots()` 返回不可变副本。
- 在 AgentLoop 中，ReadFileTool 执行成功后立即调 `contextManager.recordFileRead(path, rawContent)`（F19a）。

### TokenEstimator（升级）

**职责：** 锚定真实 usage，增量估算。

**变更：**
- `ESTIMATE_CHARS_PER_TOKEN` 从 3.0 改为 3.5。
- 锚点更新逻辑：`usage.inputTokens + usage.cacheReadTokens + usage.cacheCreationTokens + usage.outputTokens`（F14）。

### Tui 改造

**职责：** 命令注册表 + 系统消息展示。

**关键点：**
- 新增 `BUILTIN_COMMANDS` 注册表（Map<String, Command>）：`/exit`, `/plan`, `/do`, `/perm`, `/compact`, `/help`。
- 输入以 `/` 开头时走命令路径，不发 LLM（F21）。
- `/compact` 同步调用 `contextManager.manualCompact()`，完成后显示 CompressEvent 结果（F24, F24a, F24b）。
- 新增 `displaySystemMessage(String)` 方法，在 TUI 中展示系统消息。

### AgentLoop 改造

**职责：** 接入紧急压缩 + 文件追踪。

**关键点：**
- 每轮 `run` 开始处调 `contextManager.autoManage()`。
- 工具执行成功后，ReadFile 结果调 `contextManager.recordFileRead()`（F19a）。
- `provider.chatStream()` 捕获 `prompt_too_long` 错误 → 调 `contextManager.emergencyCompact()` → 重试一次（F25, F26）。
- 重试用 `emergencyRetried` 标记保证一次迭代内只重试一次（F26）。

---

## 文件组织

```
EasyCode/
├── src/main/java/com/easycode/context/
│   ├── ContextManager.java      ── 重写：总调度（auto/手动/紧急 三入口）
│   ├── ReplacementLedger.java   ── 新：替换决策账本（线程安全）
│   ├── Offloader.java           ── 新（替换 ToolResultTruncator）：F1+F2 联合落盘
│   ├── SummaryGenerator.java    ── 重写：9部分摘要 + PTL 重试
│   ├── RecoveryBuilder.java     ── 新：恢复三段构造
│   ├── FileTracker.java         ── 新：文件追踪
│   ├── SessionManager.java      ── 新：会话 ID/目录
│   ├── TokenEstimator.java      ── 改：chars/3.5 + 全量 usage 锚定
│   ├── Constants.java           ── 新：硬编码常量
│   └── CompressEvent.java       ── 新：压缩事件 record
├── src/main/java/com/easycode/agent/
│   └── AgentLoop.java           ── 改：紧急压缩 + 文件追踪 + /compact
├── src/main/java/com/easycode/tui/
│   └── Tui.java                 ── 改：命令注册表 + 系统消息
├── src/main/java/com/easycode/conversation/
│   └── ConversationMgr.java     ── 微改：assembleMessages()
├── src/main/java/com/easycode/config/
│   └── Config.java              ── 改：加 contextWindow + 默认值逻辑
└── src/test/java/com/easycode/context/
    ├── ContextManagerTest.java  ── 改：覆盖三入口 + 熔断
    ├── OffloaderTest.java       ── 新：F1/F2 落盘 + 预览体
    ├── ReplacementLedgerTest.java ── 新：决策冻结 + 并发
    ├── RecoveryBuilderTest.java ── 新：恢复三段
    └── SummaryGeneratorTest.java ── 新：9部分摘要 + PTL 重试
```

---

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 账本用 ReentrantLock 而非 synchronized | ReentrantLock | 需要 tryLock 用于非阻塞路径，且可中断 |
| 预览体字节口径 | UTF-8 字节长度 | 跨平台一致，中文 3 字节明确 |
| 文件追踪触发时机 | ReadFile 成功后同步记录 | F19a 要求同一线程顺序发生 |
| 恢复段工具列表 | 与请求 tools 同一引用（==） | F17 要求严格一致，不独立重算 |
| 命令系统 | Map<String, Command> 注册表 | F21 统一管理，可扩展 |
| 紧急压缩是否共用摘要路径 | 是，emergencyCompact 内部调同一 summarize 方法 | G5 要求共用核心路径 |
| 摘要请求自身 PTL 处理 | 消息组丢弃策略（3次直接 + 20% 比例丢） | F27 |
| contextWindow 默认值 | anthropic=200000, openai=128000 | F31 |
| 会话目录 | `.EasyCode/sessions/<session_id>/tool-results/` | F35 |

---

## 与现有实现的对应关系

| 旧文件 | 新文件 | 变化 |
|--------|--------|------|
| `ToolResultTruncator.java` | `Offloader.java` | 重写：单阈值→双阈值，加账本，加预览体格式 |
| `TokenEstimator.java` | `TokenEstimator.java` | 升级：比值 3→3.5，锚点含全量 usage |
| `SummaryGenerator.java` | `SummaryGenerator.java` | 重写：简单 prompt→9部分结构 + PTL 重试 |
| `ContextManager.java` | `ContextManager.java` | 重写：单一入口→三入口，加熔断，加恢复段 |
| 无 | `ReplacementLedger.java` | 新：决策冻结 + 并发安全 |
| 无 | `RecoveryBuilder.java` | 新：文件快照 + 工具列表 + 边界提示 |
| 无 | `FileTracker.java` | 新：最近读取文件追踪 |
| 无 | `SessionManager.java` | 新：会话 ID/目录 |
| 无 | `Constants.java` | 新：硬编码常量集合 |
| 无 | `CompressEvent.java` | 新：压缩事件 |
| `AgentLoop.java` | `AgentLoop.java` | 改：接入三入口 + 紧急压缩 + 文件追踪 |
| `Tui.java` | `Tui.java` | 改：命令注册表 + 系统消息展示 |
| `ConversationMgr.java` | `ConversationMgr.java` | 微改：新增 assembleMessages |
| `Config.java` | `Config.java` | 改：增加 contextWindow |
