<!-- 生成时间: 2026-06-17 -->
# EasyCode 纯对话阶段 Spec

## 背景

从零构建 EasyCode——一个终端 AI 编程助手（类似 Claude Code）。项目已有 pom.xml（Java 17）和 src/ 骨架，但原内容为 Java Agent，需改造。本阶段聚焦纯对话能力，暂不涉及 tool use、文件操作、代码编辑等 agent 功能。

## 目标

- 用户在终端启动 EasyCode 后，进入基于 JLine 的交互式对话界面
- 用户输入问题，EasyCode 调用 LLM API（支持 Anthropic Claude 和 OpenAI），将回复逐字流式打印
- 支持多轮对话，AI 在会话内记住历史上下文
- Provider 层通过统一接口抽象，方便后续接入新后端

## 功能需求

- F1: YAML 配置加载——启动时从当前工作目录读取 `easycode.yaml`，解析 `protocol`、`model`、`base_url`、`api_key` 四个字段；文件缺失或字段不全时给出明确错误提示并退出。
- F2: CLI 入口与启动——用户在终端执行程序后进入 JLine 交互界面，显示欢迎信息和提示符，等待用户输入。
- F3: 交互式对话输入——用户在 JLine 提示符下键入问题，按回车发送；支持多行输入。
- F4: LLM 调用（Anthropic）——通过 Anthropic Messages API 发送对话请求，携带完整对话历史；支持 extended_thinking 参数。
- F5: LLM 调用（OpenAI）——通过 OpenAI Chat Completions API 发送对话请求，携带完整 messages 数组。
- F6: 流式逐字输出——两种后端均使用 SSE 接收回复，每收到一个 token 立即打印，不等待完整回复。
- F7: 多轮对话记忆——会话内维护消息历史列表，每次请求携带完整历史；程序关闭后历史不保留。
- F8: Provider 统一抽象——定义 LlmProvider 接口，Anthropic 和 OpenAI 各自实现，配置切换不改变调用方代码。
- F9: 特殊命令——支持 `/exit` 退出、`/help` 显示可用命令。

## 非功能需求

- N1: 启动时间——从执行命令到显示提示符应在 2 秒内完成。
- N2: 首 token 延迟——网络正常条件下不超过 3 秒。
- N3: 流式渲染帧率——token 以流式到达的速率直接打印，不积攒批量输出。
- N4: 错误处理——网络异常、API 返回错误时输出可理解的错误信息，不打印原始堆栈，不崩溃退出（配置缺失除外）。
- N5: 可测试性——Provider 接口和配置解析逻辑可独立于 TUI 进行单元测试。

## 不做的事

- Tool use / function calling
- 文件读写、代码编辑、命令执行
- 对话历史持久化（关闭即清）
- 多会话管理、会话切换
- 对话导出、Markdown 渲染
- Token 用量统计与展示
- 配置热重载
- System Prompt 自定义

## 验收标准

- AC1（F1）：删除或故意写错 easycode.yaml，启动后看到明确的配置错误提示，程序退出。
- AC2（F2）：正常配置下启动程序，看到欢迎信息和 `>` 提示符。
- AC3（F3）：在提示符下输入文本并按回车，程序接收输入并开始调用 LLM。
- AC4（F4）：配置 `protocol: anthropic`，输入问题后看到流式返回的回复；extended_thinking 启用后思考过程正确输出。
- AC5（F5）：配置 `protocol: openai`，输入问题后看到流式返回的回复。
- AC6（F6）：回复以逐字/逐 token 方式实时打印，不是等完整回复后一次性输出。
- AC7（F7）：连续问两个相关问题，第二个回答能正确引用上文。
- AC8（F8）：切换 protocol 字段无需改动任何调用代码。
- AC9（F9）：输入 `/help` 显示命令列表；输入 `/exit` 程序正常退出。
- AC10（N4）：模拟 API 返回 401/500 或网络不可达，程序输出可读错误信息，不崩溃。
