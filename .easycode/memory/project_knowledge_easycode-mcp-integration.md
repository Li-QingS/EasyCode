---
type: project_knowledge
title: EasyCode MCP 接入架构
created: 2026-06-20T18:13:48.31603805+08:00
updated: 2026-06-20T18:13:48.31603805+08:00
---

MCP 接入分五层：1) 配置加载：用户级 ~/.easycode/mcp.yaml 和项目级 easycode.yaml 合并；2) 传输层：StdioTransport (启动子进程) 和 HttpTransport (HTTP POST)；3) 协议层：基于 JSON-RPC 2.0，使用 CompletableFuture + ConcurrentHashMap 实现异步响应，30秒超时；4) McpManager 使用线程池并发连接每个 MCP 服务器，完成 initialize 和 listTools 后为每个远端工具创建 McpToolAdapter；5) McpToolAdapter 将远端工具封装为 Tool 接口，命名格式 mcp__{serverName}__{toolName}，根据 readOnly 设置权限和分类，实现 execute 调用 callTool。进程退出时通过 shutdown hook 并发关闭所有 client。
