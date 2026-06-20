---
type: project_knowledge
title: EasyCode 会话结束判断机制
created: 2026-06-21T00:47:03.801591076+08:00
updated: 2026-06-21T00:47:03.801591076+08:00
---

AgentLoop.run() 有8种退出路径：1) 模型返回纯文本（最常见）；2) 仅思考文本；3) 达到最大10轮工具调用；4) 用户取消；5) Provider 异常；6) 流错误；7) 连续3轮空文本；8) 连续3轮未知工具。TUI 会话通过 /exit 或 Ctrl+D 退出。
