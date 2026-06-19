# EasyCode — 终端 AI 编程助手

Java 17 实现，模块化系统提示 + 五层权限防御 + MCP 客户端，ReAct Agent Loop 自动循环调用工具直到任务完成。

## 技术栈

Java 17 + Maven | JLine 3.27.1 | Jackson 2.18.2 | JDK HttpClient (SSE) | JUnit 5 | ripgrep | MCP (JSON-RPC 2.0)

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

- 统一 `Tool` 接口: name/description/inputSchema/execute/permission/category
- 分类元信息: SEARCH/FILE/SHELL, ToolRegistry 按分类管理、按权限过滤
- 权限分级: 只读自动, 读写 `[y/n]` 确认 (可配规则放行)
- 破坏性标记: 仅 exec_command
- Plan Mode: `/plan` 仅注入只读工具, `/do` 恢复全部 (固定回 default 模式)
- 权限切换: Shift+Tab 循环四档模式; `/perm` 查看并切换
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
mvn test                      # 72 个用例
mvn -q -DskipTests package    # 打包
java -jar target/easy-code-agent-1.0-SNAPSHOT.jar   # 启动
```

## 架构

```
用户输入 → TUI (JLine 3.27.1 + spinner + Markdown→ANSI 流式渲染)
              │  AgentEvent 事件流解耦
              ▼
         AgentLoop (ReAct 主循环)
              │  ├─ 每轮: 装配稳定系统提示 + 环境信息 + reminder
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
| `CODEX.md` | 项目上下文 + 工具描述规范 + 调试原则 |
| `问题及解决方法.md` | 开发问题记录 (40+个) |
