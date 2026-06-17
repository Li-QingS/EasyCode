<!-- 生成时间: 2026-06-17 -->
# EasyCode 工具系统 Spec

## 背景

当前 EasyCode 已具备流式对话能力，但模型只能回答知识范围内的问题，无法与环境交互。需要把工具系统叠加在现有 Provider 层之上，保持协议无关的抽象。

## 目标

- 模型在流式响应中返回工具调用信息，EasyCode 在 Provider 层解析并交付上层
- 六个核心工具通过统一接口接入，执行结果回灌进对话历史
- 工具执行有超时保护和结构化错误返回
- LLM 请求体携带输入/输出 token 用量信息
- 上下文窗口管理，防止 token 超限

## 功能需求

- F1: Tool 统一接口——name(), description(), inputSchema() (JsonNode), execute(JsonNode)
- F2: 六个核心工具——read_file, write_file, edit_file, exec_command, find_files, grep_code
- F3: ToolRegistry 注册中心——登记、查找、toToolsJson()
- F4: 工具执行超时与错误处理——默认30s，超时/异常返回结构化错误
- F5: 流式工具调用解析——SSE content_block_start(tool_use) + content_block_delta(input_json_delta) 拼接 JSON 参数片段
- F6: 工具结果回灌对话历史——tool_use + tool_result block 写入 ConversationMgr
- F7: edit_file 唯一匹配替换——精确匹配一次；0或多匹配返回错误
- F8: StreamHandler 扩展——onToolCall(ToolCall), onUsage(int,int)
- F9: LlmProvider 扩展——chatStream 加 tools 参数
- F10: Token 用量跟踪——usage 解析 + StreamHandler.onUsage
- F11: 上下文窗口管理——Config.contextWindow，token 估算（字符/3），保留首条+最新user
- F12: System Prompt——Config 可配，注入首条消息
- F13: 工具调用 UI 展示——工具名称、参数、状态（执行中/完成/失败）、折叠摘要

## 非功能需求

- N1: 工具执行超时默认30s可配
- N2: exec_command 危险命令拦截（rm -rf, mkfs, fork bomb等）
- N3: 工具调用 JSON 片段即时回调，不等待完整响应
- N4: Token 估算偏差 ±30%以内
- N5: Tool.execute(), ToolRegistry, SSE 解析逻辑可独立单测
- N6: 新配置字段带默认值，旧 yaml 不改也能运行

## 不做的事

- Agent Loop（多轮工具连环调用）
- 工具并发执行
- 工具结果的会话持久化
- 用户手动选择/批准工具调用
- exec_command 交互式命令
- 工具调用日志/审计
- 上下文压缩（TODO: 后续引入摘要/滑窗混合策略）

## 验收标准

- AC1: 六个工具注册，read_file 返回内容，write_file 写入后读取验证一致
- AC2: exec_command sleep 60 超时返回错误
- AC3: 模拟 SSE 流解析出完整 ToolCall (name + 参数 JSON)
- AC4: 一问"读 pom.xml"→模型调 read_file→结果回灌→回答引用文件内容
- AC5: edit_file 一次匹配成功；0匹配报未找到；多匹配报N处
- AC6: chatStream 传入 tools 列表，请求体含 tools 字段
- AC7: onUsage(inputTokens, outputTokens) 被正确回调
- AC8: context_window:1000，20轮历史后只保留最近几轮
- AC9: 缺 context_window 时默认128000
- AC10: exec_command rm -rf / 被拦截
- AC11: 旧 yaml 不加新字段能正常启动
