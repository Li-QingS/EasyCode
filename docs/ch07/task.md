# MCP 客户端 Tasks

## T1-T9 任务列表（合并版，适配 Java 17 + 自研 JSON-RPC）

| 编号 | 任务 | 说明 |
|------|------|------|
| T1 | McpServerConfig + McpTransport 接口 | 配置 record + 传输抽象 |
| T2 | StdioTransport | ProcessBuilder 子进程 + 行帧 + stderr 线程 |
| T3 | HttpTransport | HttpClient POST + JSON 请求/响应 |
| T4 | JsonRpcHandler | 自增 id + CompletableFuture 配对 + 30s 超时 |
| T5 | McpConfigLoader | 两层 YAML + ${VAR} 展开 + 校验 |
| T6 | McpClient | connect → init → listTools → callTool |
| T7 | McpToolAdapter | 远端工具适配 Tool 接口 |
| T8 | McpManager | CachedThreadPool 并发连接 + 30s 超时 + 注册 + ShutdownHook |
| T9 | Config + ModeDeductionLayer + Main 改造 | 接入 + categoryIndex 修复 |
