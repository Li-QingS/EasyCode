# EasyCode — 终端 AI 编程助手

Java 17 实现，ReAct Agent Loop 自动循环调用工具直到任务完成。

## 技术栈

Java 17 + Maven | JLine 3.x | Jackson | JDK HttpClient (SSE) | JUnit 5 | ripgrep

## 工具矩阵

| 工具 | 分类 | 权限 | 确认 | 破坏性 | 功能 |
|------|------|------|------|--------|------|
| `read_file` | FILE | 只读 | 否 | 否 | 读取文件(行号+offset+limit) |
| `write_file` | FILE | 读写 | 是 | 否 | 创建/覆盖文件 |
| `edit_file` | FILE | 读写 | 是 | 否 | 唯一匹配替换(返回diff) |
| `exec_command` | SHELL | 读写 | 是 | **是** | shell命令(退出码语义表) |
| `find_files` | SEARCH | 只读 | 否 | 否 | glob递归(时间倒序, 排无意义目录) |
| `grep_code` | SEARCH | 只读 | 否 | 否 | 正则搜索(rg, 支持fileFilter/contextLines) |

- 统一 `Tool` 接口: name/description/inputSchema/execute/permission/category
- 分类元信息: SEARCH/FILE/SHELL, ToolRegistry 按分类管理、按权限过滤
- 权限分级: 只读自动, 读写 `[y/n]` 确认
- 破坏性标记: 仅 exec_command
- Plan Mode: `/plan` 仅注入只读工具, `/do` 恢复全部
- 退出码语义表: grep/diff/find 退出码1≠错误
- 路径清洗统一: `Tool.resolvePath()` 反斜杠/Windows盘符自动转换

## 配置

```yaml
protocol: anthropic
model: deepseek-v4-flash
base_url: https://api.deepseek.com/anthropic
api_key: sk-xxx
# 可选: context_window, tool_timeout, system_prompt
```

## 构建与运行

```bash
cd "/mnt/d/agent project/EasyCode"
mvn compile          # 编译
mvn test             # 31 个用例
java -cp "target/classes:$(cat /tmp/cp.txt)" com.easycode.Main   # 启动
```

## 架构

```
用户输入 → TUI (JLine + spinner + Markdown→ANSI 流式渲染)
              │  AgentEvent 事件流解耦
              ▼
         AgentLoop (ReAct 主循环, 最多10轮, 5种停止条件)
              │  ├─ 每轮: 构建tools JSON → provider.chatStream()
              │  ├─ StreamingCollector 双路收集(实时+累积)
              │  ├─ thinking 兜底(text为空时用thinking回复)
              │  └─ 完成判定: toolCalls空+text非空 → 返回
              │
              ▼
         ToolExecutor (连续只读并发, 有副作用串行, 30s超时)
              │
              ▼
         AnthropicProvider / OpenAIProvider (SSE + sendAsync)
              │  ├─ text_delta → onToken → TextDelta事件
              │  ├─ thinking_delta → onThinking → thinking兜底
              │  ├─ tool_use → onToolCall → 执行工具 → 灌回历史
              │  └─ usage → onUsage → 累计Token统计
              │
              ▼
         ToolRegistry (按Category分类, 按Permission过滤)
```

## 设计文档

`docs/ch02/` — 纯对话 spec/plan/task/checklist
`docs/ch03/` — 工具系统 spec/plan/task/checklist
`docs/ch04/` — Agent Loop spec/plan/task/checklist
`CODEX.md` — 项目上下文 + 工具描述规范 + 调试原则
`问题及解决方法.md` — 开发问题记录 (30+个)
