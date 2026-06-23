# 子 Agent 系统 Spec

## 背景
当前 EasyCode 只有单一 Agent 实例，所有工具调用、上下文都在同一个对话线程中串行执行。当主 Agent 需要执行复杂子任务时，工具调用结果会膨胀主对话的 token 数量，触发频繁的上下文压缩。子任务的操作与主任务的权限混在一起，无法做细粒度隔离。

## 目标
- 提供统一的 `run_agent` 工具，主 Agent 通过它委派子任务给独立子 Agent
- 支持两种模式：「定义式」从空白对话+预定义角色启动；「Fork 式」继承父 Agent 完整对话历史
- 子 Agent 拥有独立的上下文、工具集和权限模式
- 子 Agent「跑到底」模式非交互执行
- 结果异步通知回主对话，不污染主 Agent 上下文

## 功能需求

- **F1：Agent 定义加载。** 三个来源，优先级逐级覆盖：项目 `.easycode/agents/` > 用户 `~/.easycode/agents/` > 内置 `builtin/agents/`。

- **F2：统一的 run_agent 工具。** 单一工具，参数 `mode: "defined"|"fork"` 分流两条路径，工具列表始终稳定。

- **F3：定义式子 Agent。** `mode=defined`，空白对话+指定角色系统提示启动。角色名必填，可选传 `prompt`。

- **F4：Fork 式子 Agent。** `mode=fork`，继承父 Agent 完整对话历史作为初始上下文，首次 LLM 请求可命中 prompt cache。

- **F5：运行时状态隔离。** 独立 ConversationMgr、权限模式、token 计数。共享 LLM 客户端、Hook 引擎、文件系统、ToolRegistry。

- **F6：Agent 定义格式。** YAML frontmatter: name/description/tools_allow/tools_deny/model/max_turns/permission。正文为系统提示。

- **F7：工具过滤多层防线。** 全局禁止 run_agent 嵌套 → 角色 tools_deny → 后台白名单。

- **F8：跑到底非交互执行。** 内部 AgentLoop，LLM 返回无 tool_calls 即完成。

- **F9：后台任务管理。** TaskManager 追踪状态/结果/用量。三种进入后台方式：显式指定、超时自动、手动 `/bg`。Fork 强制后台。

- **F10：结果通知。** 以 ToolResult 形式回调主 Agent，包含状态、输出摘要和 token 用量。

## 非功能需求

- **N1：** 启动延迟 < 50ms
- **N2：** Fork 式 prompt cache 可命中
- **N3：** 内存隔离，后台子 Agent 数量 ≤ CPU 核数
- **N4：** 子 Agent 异常不影响主 Agent

## 不做的事
- Worktree 文件隔离
- 多 Agent 团队编排
- 后台任务跨会话持久化
- 插件级别 Agent 定义
- 子 Agent 间直接通信
- run_agent 嵌套调用

## 验收标准

- **AC1：** reviewer 角色只调用 read_file/grep_code，不调用 write_file/exec_command
- **AC2：** Fork 式子 Agent 输出包含父对话历史关键信息
- **AC3：** 子 Agent 调用 run_agent 被拦截
- **AC4：** Fork 式超时自动切后台，主 Agent 不受阻
- **AC5：** 3 个后台子 Agent 并行，TaskManager 正确追踪
- **AC6：** tools_deny 禁止 exec_command 生效
- **AC7：** 项目级 Agent 定义覆盖用户级同名定义
