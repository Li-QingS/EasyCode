# Hook 引擎 Spec

## 背景
EasyCode 当前在 Agent 生命周期的关键节点上缺乏可编程的自动化机制。每次会话开始前手动注入上下文、工具调用前人工判断是否放行、LLM 响应后手动检查格式——这些重复性工作应该由系统自动完成，但目前没有统一框架支持。

## 目标
- 提供「事件 + 条件 + 动作」三要素的声明式 Hook 规则体系
- 覆盖会话、轮次、消息、工具、系统五层生命周期
- 支持四种动作：shell 命令、提示词注入、HTTP 请求、子 Agent（占位）
- 拦截类事件统一阻止工具执行并返回拒绝原因给模型
- Hook 自身失败不中断 Agent 主流程

## 功能需求

- **F1：规则声明与加载。** 从 `easycode.hooks.yaml` 加载 Hook 规则列表，YAML 格式，启动时集中校验（字段完整性、事件名合法性、动作类型合法性），校验失败拒绝启动并输出错误详情。

- **F2：事件触发。** 支持十种事件：`startup`、`shutdown`、`session-start`、`session-end`、`turn-start`、`turn-end`、`pre-llm-request`、`post-llm-response`、`pre-tool`、`post-tool`。每个事件发生时，按规则列表顺序匹配并执行符合条件的动作。

- **F3：条件匹配。** 条件表达式复用权限规则语法（精确匹配 `equals`、反向匹配 `not-equals`、正则 `regex`、glob `glob`），逻辑组合用 `all`（全部满足）或 `any`（任一满足）二选一，不混用。条件字段可省略表示无条件触发。

- **F4：工具拦截。** `pre-tool` 事件匹配时，工具不执行，动作输出作为 `ToolResult`（`isError=true`）返回给模型，让模型根据拒绝原因调整后续行为。拦截类事件不允许异步执行。

- **F5：Shell 命令动作。** 执行指定的 shell 命令，支持 `cwd` 工作目录和 `env` 环境变量，支持 `timeout` 超时（默认 30 秒）。命令 stdout/stderr 截断后作为动作输出。

- **F6：提示词注入动作。** 将指定文本注入到对应上下文位置。`pre-llm-request` 事件时注入到 System Prompt 末尾，`turn-start` 时注入到本轮用户消息前，`session-start` 时作为会话初始化上下文。

- **F7：HTTP 请求动作。** 发送 HTTP 请求到指定 URL，支持 `method`、`headers`、`body` 配置。响应状态码和响应体（截断）作为动作输出。支持 `timeout`。

- **F8：子 Agent 动作（占位）。** 规则解析和动作类型注册支持 `sub-agent` 类型，但执行时输出 "[sub-agent not yet implemented]" 占位信息，不报错。

- **F9：执行控制。** 每条规则支持 `once`（进程生命周期内只跑一次）、`async`（后台执行，不等待结果）。拦截类事件（`pre-tool`）不允许 `async`。

- **F10：容错隔离。** Hook 自身异常只记录日志（包含规则名、事件、错误信息），绝不中断 Agent 主流程。一个 Hook 失败不影响后续 Hook 的执行。

## 非功能需求

- **N1：启动校验。** YAML 加载和校验在 100ms 内完成（对于 ≤50 条规则），不拖慢 EasyCode 启动速度。
- **N2：Hook 执行开销。** 单条 Hook 的条件匹配 + 动作执行在 100ms 内完成（shell/HTTP 动作不计网络 IO 和命令执行时间），对 Agent 轮次延迟影响可忽略。
- **N3：日志可追溯。** 每条 Hook 的执行结果（匹配/不匹配、成功/失败、耗时 ms）写入日志，格式统一，方便排查。
- **N4：扩展性。** 新增事件类型或动作类型只需新增一个实现类 + 注册，不修改核心调度逻辑。

## 不做的事

- 子 Agent 动作的真实运行（占位即可）
- 「只跑一次」标记的持久化（`once` 仅进程生命周期内生效）
- Hook 执行顺序的显式优先级（按 YAML 中声明顺序执行）
- 条件表达式支持嵌套逻辑组合（只支持 `all` 或 `any` 一层）
- Hook 热更新（修改 YAML 需重启）
- 工具级 `pre-tool` 对 MCP 工具的参数拦截（第一阶段仅覆盖内置工具）

## 验收标准

- **AC1：** 启动 EasyCode，`easycode.hooks.yaml` 包含 1 条 `session-start → 提示词注入` 规则，验证 System Prompt 中出现了注入的文本。
- **AC2：** 配置 1 条 `pre-tool → exec_command → all: equals(name, exec_command) contains(command, rm)` 规则，发送 `rm -rf /` 工具调用，验证工具被拦截且模型收到 `isError=true` 的拒绝原因。
- **AC3：** 配置 1 条包含语法错误的规则，启动时拒绝启动，stderr 输出具体错误字段和行号。
- **AC4：** 配置 1 条 `post-llm-response → shell` 规则，shell 命令故意写错（`exit 1`），验证 Hook 失败日志被记录，Agent 正常继续下一轮对话。
- **AC5：** 配置 3 条不同事件的规则（`turn-start`、`pre-tool`、`post-tool`），完成一轮带工具调用的完整对话，验证日志中三条规则的执行记录按顺序出现。
- **AC6：** 配置 1 条 `startup` + `once` 的 shell 规则，重启 EasyCode 两次，验证命令只在第一次启动时执行，第二次跳过。
