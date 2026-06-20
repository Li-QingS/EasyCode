---
type: project_knowledge
title: EasyCode 会话管理实现
created: 2026-06-20T20:33:22.217411268+08:00
updated: 2026-06-20T20:33:22.217411268+08:00
---

会话管理由 SessionContext、ConversationMgr 和 ReplacementLedger 三个核心类实现。SessionContext 生成格式为 YYYYMMDD-HHMMSS-xxxx 的会话 ID，管理 .easycode/sessions/ 目录下的 conversation.jsonl 和 tool-results/。ConversationMgr 用 List<MessageRecord> 维护对话历史，实现 addUserMessage/addAssistantMessage/addToolUse/addToolResult 等方法，通过 fixRoleAlternation() 修复角色交替、trimToWindow() 进行 Token 裁剪，结合 ReplacementLedger 执行工具结果摘要替换后组装发送消息。ReplacementLedger 是线程安全的替换决策账本，支持原子决策和查询已替换内容。三者协同实现对话的创建、追加、裁剪和持久化。
