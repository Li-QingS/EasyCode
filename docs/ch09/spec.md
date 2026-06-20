# ch09 跨会话记忆 Spec

## 背景
EasyCode 当前无状态：每次启动都是全新会话，不知道用户是谁、项目有什么规范、上次聊到哪里。ch08 解决了「单进程内长时间工作不崩」的问题，但进程一退出，所有对话历史和工作上下文全丢。

本章通过三套机制——项目指令文件、会话存档、自动笔记——实现跨会话记忆。

## 目标
- G1: 新会话自动加载项目指令和记忆索引，第一轮就能遵循项目规范
- G2: JSONL 追加写，崩溃最多丢最后一行；恢复时处理坏行等异常
- G3: `/resume` 命令从历史会话列表选择恢复
- G4: 每轮后自动提取值得记住的信息为持久化笔记
- G5: @include 支持嵌套+环路检测
- G6: 对现有 Agent 主循环影响最小
- G7: 30 天过期会话自动清理
- G8: session ID 改 `YYYYMMDD-HHMMSS-xxxx`

## 功能需求

### 第 1 层：项目指令文件
- **F1**: 三层路径顺序加载: ① `<root>/EasyCode.md` ② `<root>/.easycode/EasyCode.md` ③ `~/.easycode/EasyCode.md`
- **F2**: `@include <path>` 独占一行时替换为文件内容，支持嵌套
- **F3**: 嵌套深度 ≤5，超深保留原文+警告注释
- **F4**: visited 集合防环路
- **F5**: 路径逃逸检测：项目级不跳出 root，用户级不跳出 `~/.easycode/`
- **F6**: 静默跳过缺失/空/二进制文件
- **F7**: 注入 custom-instructions 模块 (priority 80)
- **F8**: 启动时加载一次，进程内缓存

### 第 2 层：会话存档 (JSONL)
- **F9**: session ID: `YYYYMMDD-HHMMSS-xxxx`
- **F10**: sessionDir = `sessions/<id>/`, JSONL = `conversation.jsonl`
- **F11**: JSONL 字段: role/content/tool_calls/tool_results/ts/model(首条)
- **F12**: 压缩标记行 `{"type":"compact","ts":...}`
- **F13**: 回调追加：addUser/addAssistant/addToolResults/replaceMessages 后写 JSONL
- **F14**: 崩溃安全：只追加写，最多丢最后一行
- **F15**: Writer 用 ReentrantLock 保证多线程追加原子性
- **F16**: Writer 实现 Closeable

### 第 3 层：会话恢复
- **F17**: `/resume` 仅在 IDLE 状态可用
- **F18**: 扫描 sessions/ 下含 conversation.jsonl 的目录，按 mtime 倒序
- **F19**: JLine Completer 实现列表 UI（上下键+搜索+Enter+Esc）
- **F20**: 展示：标题(截断50字)/相对时间/模型标签/文件大小
- **F21**: 恢复流程：读 JSONL→跳坏行→截断孤立 tool→超限压缩→时间提醒
- **F22**: 恢复后切换会话：重建 Conversation + 重开 Writer（追加模式）
- **F23**: TUI 显示「已恢复会话 <id>，共 N 条消息」
- **F24**: 原新会话 JSONL 保留不删

### 会话清理
- **F25**: 启动时删 30 天以上 session 目录
- **F26**: virtual thread 后台执行，失败跳过

### 第 4 层：自动笔记 (Memory)
- **F27**: 四类：user_preference/correction_feedback/project_knowledge/reference_material
- **F28**: Markdown + YAML frontmatter
- **F29**: 两级：`.easycode/memory/` + `~/.easycode/memory/`
- **F30**: 索引 MEMORY.md，≤200行/25KB，超出由 LLM 合并/淘汰
- **F31**: 文件名 `<type>_<slug>.md`
- **F32**: 启动/更新后读索引注入 long-term-memory 模块 (priority 100)
- **F33**: 注入索引纯文本，非笔记全文
- **F34**: 超过 25KB 截断 + truncated 标注
- **F35**: 触发：每 5 轮 OR 含记忆关键词
- **F36**: 异步 virtual thread，不阻塞用户输入
- **F37**: 更新输入 = 最近对话 + 现有索引，同 provider
- **F38**: 无工具定义
- **F39**: LLM 返回 JSON 数组 [{action,level,type,title,slug,content},...]
- **F40**: create/update/delete 三种操作
- **F41**: 去重交给 LLM
- **F42**: 失败静默，不重试

### 集成
- **F43**: buildSystemPrompt(instructions, memory) 两个新参数
- **F44**: Conversation 构造时可选 onAppend/onReplace 回调
- **F45**: Main 启动新增四步
- **F46**: /resume 与 Agent.run 互斥
- **F47**: 记忆更新与 /compact 可并发

## 非功能需求
- **N1**: 指令加载 ≤200ms, JSONL append ≤10ms, 50 会话扫描 ≤500ms
- **N2**: Writer 线程安全, memory 文件操作用 ReentrantLock
- **N3**: 无 EasyCode.md / memory 目录 / 旧格式 session 均不阻塞启动
- **N4**: 核心逻辑可单元测试
- **N5**: 各级降级策略

## 不做的事
- 不做向量数据库/RAG/团队同步/自动恢复/会话合并/质量反馈/热更新/全文搜索/旧格式清理

## 验收标准
AC1-AC6: 项目指令 | AC7-AC10: 会话存档 | AC11-AC18: 会话恢复 | AC19-AC20: 清理 | AC21-AC26: 自动笔记 | AC27-AC29: 集成
