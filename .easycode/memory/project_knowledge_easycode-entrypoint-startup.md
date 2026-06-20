---
type: project_knowledge
title: EasyCode 项目入口与启动流程
created: 2026-06-20T18:13:48.253390755+08:00
updated: 2026-06-20T18:13:48.253390755+08:00
---

入口文件：src/main/java/com/easycode/Main.java，39行。启动顺序：ConfigLoader.load("easycode.yaml") 加载配置 → ProviderFactory.create(config) 创建 LLM 提供商 → ToolRegistry 注册6个内置工具 → ConversationMgr 初始化 → PermissionPipeline 加载权限 → McpManager 发现并注册 MCP 扩展工具，注册JVM关闭钩子 → AgentLoop 核心循环 → Tui 终端UI启动。
