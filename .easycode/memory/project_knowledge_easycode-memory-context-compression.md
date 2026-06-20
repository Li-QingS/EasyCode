---
type: project_knowledge
title: EasyCode 记忆系统（上下文压缩）实现
created: 2026-06-20T20:34:54.964867618+08:00
updated: 2026-06-20T20:34:54.964867618+08:00
---

记忆系统（上下文压缩）采用两层+三条路径架构，由 ContextManager 统一调度。

## 第一层：Offloader
- 规则 F1：单条工具结果超过 20,000 字节 → 落盘，替换为预览体（前20行/2048字节 + 文件路径）。
- 规则 F2：同一消息内工具结果总字节数超过 200,000 → 按字节倒序逐步落盘至阈值以下。
- 落盘目录：.easycode/sessions/<id>/tool-results/
- 预览体格式：包含原始字节数、头几行内容和完整文件路径。

## 第二层：SummaryGenerator
- 触发条件：
  - 自动：每轮估算 token 超阈值（contextWindow - 20000 - 13000）且连续失败<3次
  - 手动：/compact 命令
  - 紧急：API 返回 prompt_too_long
- 流程：findKeepIndex() 保留至少 10,000 token 和 5 条消息 → 调用 LLM 生成 9 部分结构化摘要 → 提取 <summary> 内容 → 构建新历史（摘要+恢复段+边界ASSISTANT+近期原文）。
- 恢复段包含最近5个文件快照、当前工具列表和边界提示（禁止脑补）。
- PTL 重试：前3次直接重试，之后按20%比例丢弃消息组重试。

## 三条调用路径
- autoManage()：自动执行，每次先执行 offload，后判断阈值调摘要。
- manualCompact()：手动触发，直接调摘要。
- emergencyCompact()：紧急触发，先 offload 再摘要，并后校验。
路径共享 ReentrantLock 保证互斥。

主要涉及文件：ContextManager.java、Offloader.java、SummaryGenerator.java、CompressEvent.java、RecoveryBuilder.java、Constants.java、ReplacementLedger.java。
