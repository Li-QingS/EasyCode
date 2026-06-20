---
type: project_knowledge
title: EasyCode 会话管理实现
created: 2026-06-20T20:33:33.843717234+08:00
updated: 2026-06-20T20:33:33.843717234+08:00
---

会话管理由 SessionContext、ConversationMgr 和 ReplacementLedger 三个核心类协作实现。SessionContext 生成格式为 YYYYMMDD-HHMMSS-xxxx 的会话 ID，管理会话目录（conversation.jsonl 持久化对话历史、tool-results/ 保存工具结果）。ConversationMgr 维护 List<MessageRecord> 对话历史，提供添加用户/助手/工具消息的方法，支持 fixRoleAlternation() 修复角色交替、trimToWindow(maxTokens) 按 token 裁剪旧消息、assembleMessages(ReplacementLedger) 结合替换账本组装 API 请求消息。ReplacementLedger 是线程安全的决策账本，管理长工具结果到摘要的替换，保证同一 toolUseId 只决策一次。三者协同完成对话的创建、追加、裁剪和持久化。
