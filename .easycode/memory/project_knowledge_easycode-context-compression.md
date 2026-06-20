---
type: project_knowledge
title: EasyCode 上下文压缩机制
created: 2026-06-20T20:34:55.430028177+08:00
updated: 2026-06-20T20:34:55.430028177+08:00
---

压缩采用两层+三条路径架构。第1层Offloader本地落盘工具结果（F1单条>20KB落盘替换为预览，F2单轮聚合>200KB落盘至阈值以下），轻量且始终执行。第2层SummaryGenerator调用LLM生成9部分结构化摘要，包括请求意图、关键概念、文件代码段、错误修复、解决过程、用户原话、待办、当前工作、下一步。触发路径：autoManage每轮自动检查token超阈值触发，manualCompact由/compact命令无条件触发，emergencyCompact在API返回prompt_too_long时触发。压缩时保留最后10K token和5条消息，插入恢复段（最近文件快照、工具列表、边界提示）防止模型脑补。三条路径共享ReentrantLock互斥锁。
