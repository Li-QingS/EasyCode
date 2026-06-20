# ch09 跨会话记忆 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `instructions/InstructionLoader.java` | 三层加载总入口 |
| 新建 | `instructions/IncludeResolver.java` | @include 展开引擎 |
| 新建 | `session/SessionContext.java` | ID(新格式) + 目录(替代 SessionManager) |
| 新建 | `session/SessionWriter.java` | JSONL 追加写 |
| 新建 | `session/SessionResumer.java` | 扫描+恢复 |
| 新建 | `session/SessionCleaner.java` | 30天清理 |
| 新建 | `memory/MemoryStore.java` | 笔记 CRUD + 索引 |
| 新建 | `memory/MemoryInjector.java` | 索引→prompt 注入 |
| 新建 | `memory/MemoryUpdater.java` | 异步 LLM 更新 |
| 改 | `context/SessionManager.java` | 委托给 SessionContext（兼容 ch08） |
| 改 | `prompt/Prompt.java` | buildSystemPrompt 加参数 |
| 改 | `conversation/ConversationMgr.java` | onAppend/onReplace 回调 |
| 改 | `agent/AgentLoop.java` | run() 后触发 MemoryUpdater |
| 改 | `tui/Tui.java` | /resume + 列表 UI |
| 改 | `Main.java` | 启动流程插入 4 步 |
| 新建 | `instructions/InstructionLoaderTest.java` | @include 展开 / 环路 / 逃逸 测试 |
| 新建 | `session/SessionResumerTest.java` | JSONL 恢复 / 坏行 / 孤立 测试 |
| 新建 | `memory/MemoryStoreTest.java` | CRUD + 索引 测试 |

## T1: @include 展开引擎

**文件：** `instructions/IncludeResolver.java`, `instructions/InstructionLoader.java`
**依赖：** 无
**步骤：**
1. 创建 `IncludeResolver.java`：递归解析 `@include <path>` 行
   - 独占行匹配：`line.trim().startsWith("@include ")` 且不包含其他内容
   - 相对路径解析：`baseDir.resolve(relativePath).normalize()`
   - 嵌套深度 ≤5，超深跳过+警告注释 `<!-- @include 超过最大嵌套深度，已跳过: <path> -->`
   - visited 集合：`Set<String>` 存已解析的规范绝对路径，命中时跳过+环路警告
   - 逃逸检测：`resolvedPath.startsWith(rootBoundary)`，失败时跳过+范围警告
   - 文件不存在/二进制/异常：静默跳过或警告
2. 创建 `InstructionLoader.java`：三层加载
   - `load(Path root)` 按 F1 顺序读 3 个路径，每个调用 IncludeResolver
   - 拼接时高优先级在前，空行分隔
   - 异常降级为空字符串
   - 结果缓存到 static volatile 字段

**验证：** `mvn test -Dtest=InstructionLoaderTest`（6 层嵌套截断/环路/逃逸/三层拼接）

## T2: SessionContext（替代 SessionManager）

**文件：** `session/SessionContext.java`, 改 `context/SessionManager.java`
**依赖：** 无
**步骤：**
1. 创建 `SessionContext.java`：
   - `newSessionId()`：`LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + 4字符随机hex`
   - `sessionDir(id)`：`.easycode/sessions/<id>/`
   - `toolResultDir(id)`：`sessionDir + "tool-results/"`
   - `jsonlPath(id)`：`sessionDir + "conversation.jsonl"`
   - `parseTimestamp(id)`：从 ID 前缀解析时间
2. 修改 `SessionManager.java`：保持类名和方法签名不变，内部委托给 `SessionContext`（兼容 ch08 所有调用点）

**验证：** `mvn test` 全部通过（ch08 测试不变）

## T3: JSONL 追加写

**文件：** `session/SessionWriter.java`
**依赖：** T2
**步骤：**
1. 创建 `SessionWriter.java`：
   - `BufferedWriter` + `ReentrantLock`
   - `append(MessageRecord, model)`：序列化为一行 JSON，写入 + flush + sync
   - `appendCompactMarker()`：写 `{"type":"compact","ts":<unix_ts>}` 行
   - `appendAll(List, model)`：逐条追加
   - 实现 `Closeable`
   - 序列化：`ObjectMapper` 将 MessageRecord 转为 `{role, content, tool_calls/tool_results, ts, model}`
   - 仅第一条消息携带 model 字段

**验证：** 编译通过，Writer 单元测试（追加+flush+多线程安全）

## T4: 会话恢复 + 清理

**文件：** `session/SessionResumer.java`, `session/SessionCleaner.java`
**依赖：** T2, T3
**步骤：**
1. 创建 `SessionResumer.java`：
   - `scanAll(Path root)`：遍历 `sessions/` 子目录，读首条 user 消息截断 50 字为标题
   - `resume(Path jsonl, int ctxWin, LlmProvider)`：逐行解析 → 跳坏行 → 从最后的 compact 标记开始 → 孤立 tool 截断 → token 超限压缩 → 时间提醒
   - 相对时间格式化：`Duration.between(...).toHours()/toDays()`
2. 创建 `SessionCleaner.java`：
   - `clean(Path root)`：virtual thread，遍历子目录，解析 timestamp → 超 30 天 → 递归删目录
   - 解析失败跳过

**验证：** `mvn test -Dtest=SessionResumerTest`（坏行/孤立/时间提醒）

## T5: 笔记存储 + 注入

**文件：** `memory/MemoryStore.java`, `memory/MemoryInjector.java`
**依赖：** 无
**步骤：**
1. 创建 `MemoryStore.java`：
   - 构造：`MemoryStore(Path memoryDir)`
   - `create(type, slug, title, content)`：生成文件 + 写 frontmatter + 追加索引行
   - `update(filename, title, content)`：重写文件 frontmatter + 更新索引行
   - `delete(filename)`：删文件 + 移除索引行
   - `readIndex()`：读 MEMORY.md 原文
   - 文件操作线程安全（synchronized 方法）
   - 写临时文件 + rename 保证原子性
2. 创建 `MemoryInjector.java`：
   - `build(Path projDir, Path userDir)`：读两级索引 → 拼接（项目在前）→ 超 25KB 截断 + `(index truncated)` 标注

**验证：** `mvn test -Dtest=MemoryStoreTest`（CRUD/索引/截断/原子性）

## T6: 异步记忆更新

**文件：** `memory/MemoryUpdater.java`
**依赖：** T5
**步骤：**
1. 创建 `MemoryUpdater.java`：
   - `updateAsync(provider, recentMsgs, projStore, userStore)`：virtual thread 执行
   - 构建 prompt：最近对话 + 两级索引 → 无工具请求
   - 解析 LLM 返回的 JSON 操作数组 → 调用 MemoryStore CRUD
   - 失败静默，日志记录
   - 触发条件判断（F35）由调用方（AgentLoop）负责

**验证：** mock provider 返回 JSON 操作数组 → MemoryStore 被正确调用

## T7: Conversation + Prompt 改造

**文件：** `conversation/ConversationMgr.java`, `prompt/Prompt.java`
**依赖：** T2
**步骤：**
1. 修改 `ConversationMgr.java`：
   - 新增 `Consumer<MessageRecord> onAppend` 和 `Consumer<List<MessageRecord>> onReplace` 字段
   - 重载构造函数接受回调参数（原无参构造回调为 null，行为不变）
   - `addUserMessage`/`addAssistantMessage`/`addToolResult`/`addMessage`/`replaceAll` 后触发对应回调
2. 修改 `Prompt.java`：
   - `buildSystemPrompt(String instructions, String memory)` → 非空时填入 custom-instructions(priority 80) 和 long-term-memory(priority 100) 模块
   - 保持无参 `buildStable()` 兼容

**验证：** ConversationMgr 回调计数测试；Prompt 模块拼接测试

## T8: AgentLoop 集成

**文件：** `agent/AgentLoop.java`
**依赖：** T6, T7
**步骤：**
1. `run()` 方法末尾（`AgentFinished` 事件发出后）：检查触发条件
   - `turnCount % 5 == 0` 或用户消息含记忆关键词
   - 满足条件时调用 `MemoryUpdater.updateAsync()`
2. 维护 `turnCount` 计数器
3. `forceCompact()` 仍可用，不受影响

**验证：** AgentLoopTest 中 verify MemoryUpdater 被调用

## T9: TUI /resume 命令

**文件：** `tui/Tui.java`
**依赖：** T4
**步骤：**
1. 在 `start()` 中添加 `/resume` 命令处理
2. 调用 `SessionResumer.scanAll()` 获取会话列表
3. 用 JLine `LineReader` + 自定义 `Completer` 实现列表 UI：
   - 展示标题/时间/模型/大小
   - 上下键导航 + 字符搜索过滤 + Enter 选择 + Esc 取消
4. 选择后调用 `SessionResumer.resume()` → 替换当前 conversation → 显示恢复消息
5. `/resume` 仅在 IDLE 状态可用（Agent 非运行中）

**验证：** 手动启动，输入 `/resume` → 看到会话列表

## T10: Main 启动流程 + 配置示例

**文件：** `Main.java`
**依赖：** T1, T5, T6
**步骤：**
1. 启动流程插入（在 ToolRegistry 注册之后、AgentLoop 创建之前）：
   - ① `InstructionLoader.load(root)` → 得 instructions 文本
   - ② `MemoryInjector.build(projMem, userMem)` → 得 memory 文本
   - ③ `SessionCleaner.clean(sessionsRoot)` → virtual thread 后台
   - ④ 将 instructions/memory 传入 Prompt/TUI
2. 创建 `ConversationMgr` 时注入 SessionWriter 回调

**验证：** 编译 + 启动不报错

## T11: 全量测试 + 清理

**文件：** 全部
**依赖：** T1-T10
**步骤：**
1. 清理旧 `SessionManager` 中不再使用的方法
2. 运行 `mvn test` —— 全部通过
3. `mvn -q -DskipTests package` —— 打包成功
4. 新建 session/memory 目录的 `.gitignore` 条目

**验证：** 全量测试通过，BUILD SUCCESS

## 执行顺序

```
T1(include) ──┐
              ├─→ T7(conv+prompt) ─┐
T2(session) ──┤                     ├─→ T8(agent) ──┐
              ├─→ T3(writer) ─→ T4(resume+clean) ──┤              ├─→ T11
              │                                      ├─→ T9(tui) ──┘
T5(store) ───→ T6(updater) ─────────────────────────┘
                                          │
                              T10(main) ──┘
```
