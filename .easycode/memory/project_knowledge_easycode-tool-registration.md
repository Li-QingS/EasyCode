---
type: project_knowledge
title: EasyCode 工具注册机制
created: 2026-06-20T18:13:48.298621357+08:00
updated: 2026-06-20T18:13:48.298621357+08:00
---

ToolRegistry 使用 LinkedHashMap 存储工具，key 为工具名。启动时在 Main.java 中手动注册6个内置工具。随后 McpManager.discoverAndRegister() 扫描 easycode.yaml 中的 mcpServers 配置，动态连接外部 MCP 服务并将远端工具通过 McpToolAdapter 适配后注册进同一个 ToolRegistry，实现统一调度。
