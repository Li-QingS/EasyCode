---
type: project_knowledge
title: EasyCode 架构概览
created: 2026-06-20T18:14:01.216416964+08:00
updated: 2026-06-20T18:14:01.216416964+08:00
---

项目启动入口 Main.java 顺序加载配置、创建LLM提供者、注册6个内置工具、初始化对话管理、权限、MCP扩展，最后启动Agent循环和TUI。ToolRegistry基于LinkedHashMap管理工具，支持手动注册内置工具和通过McpManager自动发现MCP扩展工具。MCP接入分为配置加载(合并用户级和项目级)、传输层(StdioTransport/HttpTransport)、协议层(JSON-RPC 2.0)、适配层(McpToolAdapter将远端工具包装为Tool接口)和注册。MCP工具命名规则为mcp__server__tool。
