# MCP 客户端 Checklist

## 编译与测试
- [ ] `mvn -q compile` 通过
- [ ] `mvn test` 全部通过（现有测试不退化）
- [ ] McpJsonRpcTest / McpConfigLoaderTest / McpToolAdapterTest 通过

## 功能验收
- [ ] AC1 配置加载与两层合并
- [ ] AC2 字段校验（缺 command/url/type → 跳过）
- [ ] AC3 ${VAR} 展开
- [ ] AC4 stdio 连接 + 列工具 + 退出终止
- [ ] AC5 HTTP 连接 + headers 注入
- [ ] AC6 工具适配命名 `mcp__<server>__<tool>`
- [ ] AC7 命名空间隔离
- [ ] AC8 启动失败隔离 + 30s 超时
- [ ] AC9 调用超时回灌
- [ ] AC10 退出干净（无僵尸进程）
- [ ] AC11 权限链路命中
- [ ] AC12 跨协议一致
- [ ] AC13 不破坏 ch01-ch06
- [ ] AC14 凭据不落盘
- [ ] AC15 代码规范
