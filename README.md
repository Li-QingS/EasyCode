# EasyCode — 终端 AI 编程助手

Java 17 实现，模块化系统提示 + 五层权限防御 + MCP 客户端，ReAct Agent Loop 自动循环调用工具直到任务完成。
支持上下文管理，两层压缩策略保证长时间对话不因 Token 溢出而中断。

## 技术栈

Java 17 + Maven | JLine 3.27.1 | Jackson 2.18.2 | JDK HttpClient (SSE) | JUnit 5 | ripgrep | MCP (JSON-RPC 2.0) | 上下文压缩

## 模块架构 (ch05)

```
prompt 包         llm 包           agent 包
(模块化装配)      (协议适配)       (编排层)
    │               │               │
    ├─ Module       ├─ System       ├─ Environment.gather()
    ├─ Prompt       ├─ Request      ├─ Reminder.plan()
    │  (fixed+optional)             │  (规划模式按轮次)
    └─ Reminder     ├─ Anthropic    └─ AgentLoop
       (system-       │  (stable缓存断点)
        reminder)     └─ OpenAI
                        (cachedTokens)
```

## 权限系统 (ch06) — 五层防御

```
工具调用 → ① 黑名单 → ② 沙箱 → ③ 规则引擎 → ④ 模式兜底 → ⑤ 人在回路
           (正则拦截   (路径前缀   (精确/glob    (权限模式     (Ask→三选一)
            高危命令)    判断)       allow/deny)   矩阵裁决)     允许本次/永久/拒绝)
```

| 模式 | 只读 | 文件写 | 命令执行 |
|------|------|--------|----------|
| DEFAULT | Allow | Ask | Ask |
| ACCEPT_EDITS | Allow | Allow | Ask |
| PLAN | Allow | Ask | Ask |
| BYPASS_PERMISSIONS | Allow | Allow | Allow |

## MCP 客户端 (ch07) — 外部工具接入

| 文件 | 职责 |
|------|------|
| `McpConfigLoader` | 两层 YAML 合并 (用户级+项目级) + ${VAR} 展开 |
| `StdioTransport` / `HttpTransport` | 子进程管道 / HTTP POST |
| `JsonRpcHandler` | 自增 id + CompletableFuture 配对 + 30s 超时 |
| `McpClient` | initialize → tools/list → tools/call 标准三步 |
| `McpToolAdapter` | 远端工具适配 Tool 接口 (mcp__server__tool 命名空间) |
| `McpManager` | 并发连接 + 失败隔离 + 5s 关闭兜底 |

**当前接入:** Context7 (2 个只读工具)

## 上下文管理 (ch08) — 两层压缩

```
每次 API 请求前:
  → ① 轻量预防: 单工具结果 > 20KB → 落盘 + 对话留预览
  → ② 重量兜底: 总 token 逼近窗口 → LLM 9 部分结构摘要
       → 恢复三段: 文件快照 + 工具列表 + 边界提示
```

| 参数 | 值 |
|------|----|
| 单条落盘阈值 | 20,000 字节 |
| 单轮聚合阈值 | 200,000 字节 |
| 自动触发余量 | 13,000 tokens |
| 手动 `/compact` | 无条件触发，3,000 tokens 余量 |
| 熔断 | 连续 3 次失败 → 停止自动触发 |
| 紧急压缩 | `prompt_too_long` → 强制第 1 层 + 摘要 + 重试一次 |
| 估算比 | chars / 3.5，锚定上次 API usage |
| 会话目录 | `.EasyCode/sessions/<unix_ts>-<hex>/` |

## 命令系统 (ch09) — 斜杠命令注册和分发

```
用户输入 → CommandParser (斜杠前缀识别)
              │
              ├─ 非 / 开头 → 作为对话消息 → AgentLoop
              └─ / 开头 → CommandDispatcher.dispatch()
                              │
                              ├─ CommandRegistry.lookup() → CommandDef
                              ├─ CommandDef.handler() → CommandResult
                              └─ Tui 按结果类型处理(Print / Prompt注入 / Exit)
```

| 命令 | 别名 | 类型 | 说明 |
|------|------|------|------|
| `/help [cmd]` | `h`, `?` | LOCAL | 列出全部或指定命令详情 |
| `/compact` | `cmp` | LOCAL | 手动触发上下文压缩 |
| `/clear` | `cls` | UI | 清屏 |
| `/plan` | — | UI | 切换到 PLAN 模式 |
| `/do` | `exec` | UI | 切换回 DEFAULT 执行模式 |
| `/session [resume <id>]` | `sess` | LOCAL | 会话信息 + 历史恢复(数字选择) |
| `/memory` | `mem` | LOCAL | 记忆存储位置和策略 |
| `/permission [mode]` | `perm`, `mode` | UI | 查看/切换权限模式(数字菜单) |
| `/status` | `stat`, `st` | LOCAL | 会话/模式/运行时长/Token 统计 |
| `/review` | `rv` | PROMPT | 注入代码审查提示词给 AI |

**架构要点：**
- `CommandRegistry` — 名称+别名映射，启动期冲突检测(冲突→terminate)，支持 Tab 补全候选生成
- `CommandParser` — 斜杠前缀识别，`/name args` → Parsed，大小写不敏感
- `UiController` 接口 — 命令实现不绑定 Tui，10 个抽象方法
- 三类命令：LOCAL(纯本地) / UI(影响界面) / PROMPT(预设提示词注入对话)
- `CommandCompleter` — JLine 集成 Tab 补全，单匹配直接补，多匹配弹菜单，隐藏命令不参与

## Skill 系统 (ch10) — 可复用任务模板

Skill 将常用工作流封装为可复用的任务模板，支持两种执行模式：

| 模式 | 机制 | 适用场景 |
|------|------|----------|
| **inline** | 注入 prompt 到当前对话，由主 AgentLoop 执行 | 简短任务，需当前上下文 |
| **fork** | 独立后台 AgentLoop + 结果回流 | 长任务，独立上下文避免污染 |

| 文件 | 职责 |
|------|------|
| `SkillLoader` | 三级目录扫描（内置 → 用户 ~/.easycode/skills → 项目 .easycode/skills），YAML frontmatter 解析 |
| `SkillFrontmatter` | 轻量元信息（name/description/mode/context/allowedTools/model） |
| `SkillRegistry` | 运行时两阶段管理：轻量列表 → 按需加载 → 激活/停用 → 工具白名单控制 |
| `SkillExecutor` | 执行引擎：inline 注入当前对话 / fork 隔离执行并回流 |
| `LoadSkillTool` | 系统工具：Agent 调用后加载并激活 Skill |
| `SkillWatcher` | 文件系统监听，`.easycode/skills/` 目录变更时热更新 |

**内置 Skill：**

| Skill | 模式 | 工具 | 说明 |
|-------|------|------|------|
| `/review` | fork | read_file, grep_code, exec_command | 代码审查（bug/安全/性能/代码异味） |
| `/commit` | inline | exec_command | 分析变更并生成规范 commit message |
| `/test` | inline | exec_command | 分析代码并生成单元测试 |

**Skill 定义格式（Markdown + YAML frontmatter）：**

```markdown
---
name: review
description: 代码审查
mode: fork
context: recent
allowedTools:
  - read_file
  - grep_code
---
请审查当前代码变更...
```

**白名单机制：** `SkillFrontmatter.allowedTools` 指定 Skill 运行期间可用的工具。inline 模式在 AgentLoop 完成时自动清空白名单，fork 模式使用独立 ToolRegistry 互不干扰。

**设计要点：**
- 三级优先级覆盖：项目 > 用户 > 内置
- fork 循环自带 `ContextManager` 自动压缩，防止长任务 Token 溢出
- `LoadSkillTool` 不受白名单限制，保证 Agent 总能加载 Skill
- `SkillWatcher` 每 2 秒轮询，通过文件级 mtime 检测内容变更触发热更新
- 目录型 Skill 激活/停用对称管理 `ToolRegistry`（`activate` 注册 → `deactivate` 注销）


## 工具矩阵

| 工具 | 分类 | 权限 | 来源 | 功能 |
|------|------|------|------|------|
| `read_file` | FILE | 只读 | 内置 | 读取文件(行号+offset+limit) |
| `write_file` | FILE | 读写 | 内置 | 创建/覆盖文件 |
| `edit_file` | FILE | 读写 | 内置 | 唯一匹配替换(返回diff) |
| `exec_command` | SHELL | 读写 | 内置 | shell命令(退出码语义表) |
| `find_files` | SEARCH | 只读 | 内置 | glob递归(时间倒序) |
| `grep_code` | SEARCH | 只读 | 内置 | 正则搜索(rg) |
| `mcp__context7__resolve-library-id` | SEARCH | 只读 | MCP | 解析库名→Context7 ID |
| `mcp__context7__query-docs` | SEARCH | 只读 | MCP | 查询最新文档和代码示例 |
| `load_skill` | SEARCH | 只读 | 内置 | 加载并激活 Skill（不受白名单限制） |

- 统一 `Tool` 接口: name/description/inputSchema/execute/permission/category
- 分类元信息: SEARCH/FILE/SHELL, ToolRegistry 按分类管理、按权限过滤
- 权限分级: 只读自动, 读写 `[y/n]` 确认 (可配规则放行)
- 破坏性标记: 仅 exec_command
- 模式切换: `/permission [mode]` 或 `/plan`/`/do` — 按数字菜单选择或直接切换
- 权限切换: `/permission` 数字菜单查看并切换，`/plan` 进入计划模式，`/do` 回到执行模式
- 上下文压缩: `/compact` 手动触发 LLM 摘要，自动触发在窗口逼近时执行
- 退出码语义表: grep/diff/find 退出码1≠错误
- 路径清洗统一: `Tool.resolvePath()` 反斜杠/Windows盘符自动转换

## 配置

```yaml
# easycode.yaml (项目级)
protocol: anthropic
model: deepseek-v4-pro
base_url: https://api.deepseek.com/anthropic
api_key: sk-xxx
# 可选 MCP 配置:
mcp_servers:
  context7:
    type: stdio
    command: npx
    args: ["-y", "@upstash/context7-mcp"]
# 用户级 MCP: ~/.easycode/mcp.yaml
# headers/env 值支持 ${VAR} 从环境变量展开
```

## 构建与运行

```bash
cd "/mnt/d/agent project/EasyCode"
mvn compile                   # 编译
mvn test                      # 82 个用例
mvn -q -DskipTests package    # 打包
java -jar target/easy-code-agent-1.0-SNAPSHOT.jar   # 启动
```

## 架构

```
用户输入 → TUI (JLine 3.27.1 + spinner + Markdown→ANSI 流式渲染)
              │  ├─ / 开头 → CommandDispatcher → Skill 匹配 → 本地命令分发 / Skill 执行
              │  └─ 非 / 开头 → AgentEvent 事件流解耦
              ▼
         AgentLoop (ReAct 主循环)
              │  ├─ 每轮: 装配稳定系统提示 + 环境信息 + reminder + 已激活 Skill prompt
              │  ├─ Skill 白名单: activeToolWhitelist() 过滤 toolsJson
              │  ├─ 构建 tools JSON → provider.stream(Request)
              │  ├─ StreamingCollector 双路收集(实时+累积)
              │  ├─ thinking 兜底(text为空时用thinking回复)
              │  └─ 完成判定: toolCalls空+text非空 → 返回
              │
              ▼
         PermissionPipeline (五层: 黑名单→沙箱→规则→模式→人在回路)
              │
              ▼
         ToolExecutor (只读并发, 有副作用串行, 30s超时)
              │
              ▼
         AnthropicProvider / OpenAIProvider (SSE + sendAsync + 缓存断点)
              │  ├─ text_delta → onToken → TextDelta事件
              │  ├─ thinking_delta → onThinking → thinking兜底
              │  ├─ tool_use → onToolCall → 执行工具 → 灌回历史
              │  └─ usage → onUsage → 累计Token统计
              │
              ▼
         ToolRegistry (内置6工具 + MCP工具统一注册)
```

## 设计文档

| 章节 | 内容 |
|------|------|
| `docs/ch02/` | 纯对话 spec/plan/task/checklist |
| `docs/ch03/` | 工具系统 spec/plan/task/checklist |
| `docs/ch04/` | Agent Loop spec/plan/task/checklist |
| `docs/ch05/` | 系统提示工程化 spec/plan/task/checklist |
| `docs/ch06/` | 权限系统 (五层防御) spec/plan/task/checklist |
| `docs/ch07/` | MCP 客户端 spec/plan/task/checklist |
| `docs/ch08/` | 上下文管理 (两层压缩) spec/plan/task/checklist |
| `docs/ch09/` | 命令系统 (注册/分发/补全) spec/plan/task/checklist |
| `docs/ch10/` | Skill 系统 (inline/fork/白名单) spec/plan/task/checklist |
| `CODEX.md` | 项目上下文 + 工具描述规范 + 调试原则 |
| `问题及解决方法.md` | 开发问题记录 (53 个) |
