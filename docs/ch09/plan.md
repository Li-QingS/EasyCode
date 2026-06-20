# ch09 跨会话记忆 Plan

## 架构概览

```
instructions/          ← 项目指令加载
├── InstructionLoader  — @include 展开 + 环路检测 + 三层合并
└── IncludeResolver    — 嵌套深度 + 路径逃逸

session/               ← 会话存档
├── SessionContext     — ID(YYYYMMDD-HHMMSS-xxxx) + 目录(替代旧 SessionManager)
├── SessionWriter      — JSONL 追加写 (ReentrantLock)
├── SessionResumer     — 扫描/恢复(坏行/孤立/超限/时间提醒)
└── SessionCleaner     — 30天清理 (virtual thread)

memory/                ← 自动笔记
├── MemoryStore        — 文件 CRUD + 索引 + frontmatter
├── MemoryInjector     — 读索引 → prompt 注入
└── MemoryUpdater      — 异步 LLM 更新

修改:
context/SessionManager  →  弃用，迁移到 SessionContext
agent/AgentLoop         →  run() 完成后触发 MemoryUpdater
conversation/ConversationMgr → onAppend/onReplace 回调
prompt/Prompt           →  buildSystemPrompt(instructions, memory)
tui/Tui                 →  /resume + JLine Completer 列表
Main                    →  启动流程插入 4 步
```

## 核心数据结构

### InstructionLoader
```java
public final class InstructionLoader {
    /**
     * 按三层顺序加载并拼接项目指令。
     * @param projectRoot 项目根目录
     * @return 拼接后的完整指令文本；三层都无文件时返回 ""
     */
    public static String load(Path projectRoot);
}
```

### IncludeResolver
```java
final class IncludeResolver {
    /**
     * @param content      EasyCode.md 原始内容
     * @param baseDir      当前文件所在目录（用于解析相对路径）
     * @param rootBoundary 根边界（项目级=projectRoot，用户级=~/.easycode/）
     * @param depth        当前嵌套深度（从 1 开始）
     * @param visited      已访问的绝对路径集合
     * @return 展开 @include 后的完整内容 + 警告注释
     */
    static String resolve(String content, Path baseDir, Path rootBoundary,
                          int depth, Set<Path> visited);
}
```

### SessionContext (替代旧 SessionManager)
```java
public final class SessionContext {
    /** 生成新 session ID: YYYYMMDD-HHMMSS-xxxx */
    public static String newSessionId();

    /** 返回 .easycode/sessions/<sessionId>/ */
    public static Path sessionDir(String sessionId);

    /** 返回 tool-results 目录 */
    public static Path toolResultDir(String sessionId);

    /** 返回 conversation.jsonl 路径 */
    public static Path jsonlPath(String sessionId);

    /** 从 session ID 中解析时间戳（用于排序/清理） */
    public static Optional<LocalDateTime> parseTimestamp(String sessionId);
}
```

### SessionWriter
```java
public final class SessionWriter implements Closeable {
    public SessionWriter(Path jsonlPath);

    /** 追加一条消息（JSON 行 + flush），线程安全 */
    public void append(MessageRecord msg, String model);

    /** 追加压缩标记行 */
    public void appendCompactMarker();

    /** 追加多条消息（replaceMessages 后） */
    public void appendAll(List<MessageRecord> msgs, String model);

    @Override public void close();
}
```

### SessionResumer
```java
public final class SessionResumer {
    /** 扫描所有有效会话，返回摘要列表（按 mtime 倒序） */
    public static List<SessionSummary> scanAll(Path sessionsRoot);

    /** 恢复指定会话：读 JSONL → 处理异常 → 返回消息列表 */
    public static ResumeResult resume(Path jsonlPath, int contextWindow,
            LlmProvider provider);

    public record SessionSummary(String id, String title, long lastModified,
            String model, long fileSize) {}

    /** timeGapHours > 6 时，reminder 非空 */
    public record ResumeResult(List<MessageRecord> messages, String reminder) {}
}
```

### MemoryStore
```java
public final class MemoryStore {
    public MemoryStore(Path memoryDir);

    /** 创建笔记文件 + 更新索引 */
    public void create(String type, String slug, String title, String content);

    /** 更新笔记文件和索引 */
    public void update(String filename, String title, String content);

    /** 删除笔记文件和索引条目 */
    public void delete(String filename);

    /** 读取索引文件内容 */
    public String readIndex();
}
```

### MemoryInjector
```java
public final class MemoryInjector {
    /** 读取两级索引，拼接后截断到 25KB */
    public static String build(Path projectMemoryDir, Path userMemoryDir);
}
```

### MemoryUpdater
```java
public final class MemoryUpdater {
    /**
     * 异步启动记忆更新。在独立 virtual thread 中执行，不阻塞调用者。
     * @param provider    LLM provider（用于生成记忆更新请求）
     * @param recentMsgs  最近对话消息（从最后一条 user 到最终 assistant 回复）
     * @param projectStore 项目级 MemoryStore
     * @param userStore    用户级 MemoryStore
     */
    public static void updateAsync(LlmProvider provider, List<MessageRecord> recentMsgs,
            MemoryStore projectStore, MemoryStore userStore);
}
```

## 模块设计

### InstructionLoader
**职责:** 三层加载 + @include 展开。启动时调用一次，结果缓存。
**关键点:**
- 内部调用 IncludeResolver 逐层处理
- 缺失文件静默跳过，异常降级为空字符串
- 结果注入 `Prompt.buildSystemPrompt()` 的 instructions 参数

### IncludeResolver
**职责:** 递归展开 @include 指令。纯函数，不依赖 I/O（文件读取在外层）。
**关键点:**
- `@include` 独占一行匹配，非独占行保留原文
- 嵌套深度 ≤5，超深不改原文 + 警告注释
- visited 绝对路径集合防环路
- 路径逃逸检测：解析后必须在 rootBoundary 内

### SessionContext
**职责:** 替代旧 `SessionManager`。统一 ID 生成和路径管理。
**关键点:**
- `newSessionId()` 用 `LocalDateTime.now()` + 4 字符随机 hex
- `parseTimestamp()` 从 ID 解析时间（用于排序和清理判断）
- 旧 `SessionManager` 相关引用全部迁移

### SessionWriter
**职责:** JSONL 追加写入，线程安全。
**关键点:**
- `BufferedWriter` + `ReentrantLock`
- 每次 append 后 `flush()` + `FileDescriptor.sync()`
- Jackson `ObjectMapper` 序列化 MessageRecord → JSON 行

### SessionResumer
**职责:** 扫描 + 恢复会话。
**关键点:**
- `scanAll()`: 遍历 `sessions/` 子目录，读首条 user 消息作为标题
- `resume()`: 逐行解析 JSONL，跳过坏行；从最后 compact 标记之后开始
- 孤立 tool 截断：最后 assistant 有 tool_calls 无对应 tool 消息 → 截断
- token 超限：调用 ch08 SummaryGenerator 压缩
- 时间提醒：最后 ts 距当前 >6h → 追加提醒消息

### SessionCleaner
**职责:** 清理过期会话目录。
**关键点:**
- virtual thread 后台执行，不阻塞启动
- 30 天阈值：`parseTimestamp(sessionId) < now - 30 days`
- 无法解析 ID 的目录跳过，不删除

### MemoryStore
**职责:** 笔记文件 CRUD + 索引管理。线程安全（ReentrantLock）。
**关键点:**
- create: 生成 `<type>_<slug>.md`，追加索引行
- update: 重写文件 frontmatter，更新索引行（匹配 filename）
- delete: 删除文件，移除索引行
- 索引 ≤200 行 / 25KB 在调用方控制（MemoryUpdater 负责）

### MemoryInjector
**职责:** 读取两级索引 → 拼接 → 截断 → 返回注入文本。
**关键点:**
- 项目级在前，用户级在后
- 总长度 >25KB 时截断，追加 `(index truncated)` 标注
- 结果传入 `Prompt.buildSystemPrompt()` 的 memory 参数

### MemoryUpdater
**职责:** 异步触发记忆更新 LLM 调用。
**关键点:**
- virtual thread 执行，不阻塞主循环
- 输入：最近对话 + 两级索引 → 同一 provider 无工具请求
- LLM 返回 JSON 操作数组 → 调用 MemoryStore CRUD
- 失败静默，不重试
- 先写临时文件再 rename 保证原子性

## 文件组织

```
EasyCode/src/main/java/com/easycode/
├── instructions/
│   ├── InstructionLoader.java    — 新: 三层加载总入口
│   └── IncludeResolver.java      — 新: @include 展开引擎
├── session/
│   ├── SessionContext.java       — 新: ID/目录(替代 SessionManager)
│   ├── SessionWriter.java        — 新: JSONL 追加写
│   ├── SessionResumer.java       — 新: 扫描+恢复
│   └── SessionCleaner.java       — 新: 30天清理
├── memory/
│   ├── MemoryStore.java          — 新: 笔记 CRUD + 索引
│   ├── MemoryInjector.java       — 新: 索引→prompt 注入
│   └── MemoryUpdater.java        — 新: 异步 LLM 更新
├── context/
│   └── SessionManager.java       — 弃用: 功能迁移到 SessionContext
├── prompt/
│   └── Prompt.java               — 改: buildSystemPrompt 加参数
├── conversation/
│   └── ConversationMgr.java      — 改: 回调 onAppend/onReplace
├── agent/
│   └── AgentLoop.java            — 改: run() 完后触发 MemoryUpdater
├── tui/
│   └── Tui.java                  — 改: /resume + 列表 UI
└── Main.java                     — 改: 启动流程插入 4 步
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 目录大小写 | 统一小写 `.easycode` | 与现有 `.easycode/mcp.yaml` 一致 |
| /resume UI | JLine Completer | 支持搜索过滤，API 统一 |
| 记忆更新原子性 | 写临时文件 + rename | 退出时无需等待 |
| JSONL 写入锁 | ReentrantLock | 主流主循环并发访问 |
| 会话清理 | virtual thread | 不阻塞启动 |
|旧 SessionManager 处理 | 保持类名，内部委托给 SessionContext | 避免大面积重命名，ch08 调用点兼容 |
