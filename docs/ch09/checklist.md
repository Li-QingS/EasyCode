# ch09 跨会话记忆 Checklist

> 每一项通过运行代码或观察行为来验证。

## 项目指令 (AC1-AC6)

- [ ] 三层加载：三个路径各放 EasyCode.md → custom-instructions 模块包含三份内容，高优先级在前（验证：InstructionLoaderTest）
- [ ] 缺失静默：仅项目根有文件 → 加载成功，只含该层内容（验证：InstructionLoaderTest）
- [ ] @include 展开：`@include rules/style.md` → 文件内容替换该行（验证：InstructionLoaderTest）
- [ ] 嵌套截断：6 层链 → 第 6 层不展开 + 深度警告注释（验证：InstructionLoaderTest）
- [ ] 环路检测：A→B→A → 第二次不展开 + 环路警告注释（验证：InstructionLoaderTest）
- [ ] 路径逃逸：`@include ../../etc/passwd` → 不加载 + 范围警告注释（验证：InstructionLoaderTest）

## 会话存档 (AC7-AC10)

- [ ] ID 格式：启动后 session ID 形如 `20260601-143022-a1b2`（验证：SessionContextTest）
- [ ] JSONL 写入：一轮对话后 conversation.jsonl 至少 2 行，每行合法 JSON 含 role/content/ts，首行含 model（验证：SessionWriterTest）
- [ ] 压缩标记：触发压缩后 JSONL 出现 `{"type":"compact","ts":...}` 标记（验证：手动或 E2E 测试）
- [ ] 崩溃安全：kill 进程后 JSONL 除最后一行外全部可解析（验证：模拟 kill 测试）

## 会话恢复 (AC11-AC18)

- [ ] /resume 路由：输入 `/resume` → 不进 LLM，进入列表；Esc → 返回（验证：手动启动）
- [ ] 列表展示：3 个会话 → 每项有标题/时间/模型/大小（验证：SessionResumerTest + 手动）
- [ ] 搜索过滤：输入关键词 → 列表过滤匹配项（验证：手动 JLine Completer）
- [ ] 坏行跳过：JSONL 中插无效行 → 恢复时被跳过（验证：SessionResumerTest）
- [ ] 孤立截断：最后 assistant 有 tool_calls 无 tool → 截断到上一条（验证：SessionResumerTest）
- [ ] Token 超限：构造大 JSONL → 恢复中自动压缩（验证：SessionResumerTest）
- [ ] 时间提醒：最后 ts 距当前 >6h → 追加时间跨度提醒（验证：SessionResumerTest）
- [ ] 追加写入：恢复后发新消息 → 追加到同一 JSONL（验证：手动验证）

## 会话清理 (AC19-AC20)

- [ ] 过期清理：31 天前目录 → 启动后被删（验证：SessionCleanerTest）
- [ ] 旧格式保护：旧 ID 格式目录 → 不删也不在列表中（验证：SessionCleanerTest）

## 自动笔记 (AC21-AC26)

- [ ] 笔记创建：表达偏好后 → memory 目录出现对应 .md 文件 + frontmatter（验证：MemoryStoreTest）
- [ ] 索引更新：创建笔记后 → MEMORY.md 出现摘要行（验证：MemoryStoreTest）
- [ ] 记忆注入：启动后 long-term-memory 模块含索引内容（验证：手动或 Prompt 测试）
- [ ] 异步不阻塞：记忆更新期间发下一条消息 → 立即处理（验证：手动验证）
- [ ] 失败静默：mock provider 返回错误 → 主会话不受影响（验证：MemoryUpdaterTest）
- [ ] 索引截断：构造 >25KB 索引 → 截断 + truncated 标注（验证：MemoryInjectorTest）

## 集成 (AC27-AC29)

- [ ] buildSystemPrompt 参数化：传非空 instructions+memory → 两模块出现；传空 → 跳过（验证：PromptTest）
- [ ] Conversation 回调：设回调后各操作触发正确次数和参数；未设回调行为不变（验证：ConversationMgrTest）
- [ ] 互斥：run 期间 /resume 返回提示；RESUMING 期间不发 run（验证：手动验证）

## 编译与测试

- [ ] `mvn -q -DskipTests compile` 通过
- [ ] `mvn test` 全部通过，0 failures
- [ ] `mvn -q -DskipTests package` 打包成功
- [ ] 旧 `SessionManager` 调用点全部兼容

## 端到端场景

- [ ] **场景 1——指令生效**：在项目根写 `EasyCode.md`（"本项目使用 Spring Boot 3"）→ 启动 EasyCode → 问"这个项目用什么框架" → 模型回答提及 Spring Boot 3（验证：手动对话）

- [ ] **场景 2——会话恢复**：对话 3 轮 → 重启 EasyCode → `/resume` → 选刚才的会话 → 继续对话 → 新消息追加到同一 JSONL（验证：手动操作）

- [ ] **场景 3——自动笔记**：对话中明确说"记住，我不喜欢总结" → 等待下一轮后 → `.easycode/memory/` 出现 `user_preference_terse_replies.md`，MEMORY.md 有对应行（验证：手动检查文件）

- [ ] **场景 4——记忆注入**：MEMORY.md 有内容 → 重启 → 第一条系统消息的 long-term-memory 模块包含索引摘要（验证：system prompt 检查）
