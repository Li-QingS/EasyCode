<!-- 生成时间: 2026-06-17 -->
# EasyCode 工具系统 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | tool/Tool.java | 工具接口 |
| 新建 | tool/ToolResult.java | 执行结果 |
| 新建 | provider/ToolCall.java | 工具调用包装 |
| 新建 | conversation/MessageBlock.java | 消息块 sealed interface |
| 修改 | conversation/MessageRecord.java | +blocks列表 |
| 新建 | tool/ReadFileTool.java 等6个 | 六个工具实现 |
| 新建 | tool/ToolRegistry.java | 注册中心 |
| 修改 | conversation/ConversationMgr.java | token估算+裁剪 |
| 修改 | provider/StreamHandler.java | +onToolCall/onUsage |
| 修改 | provider/LlmProvider.java | +tools参数 |
| 修改 | provider/AnthropicProvider.java | tool_use SSE解析+usage |
| 修改 | provider/OpenAIProvider.java | 签名对齐 |
| 修改 | config/Config.java | +contextWindow等3字段 |
| 修改 | config/ConfigLoader.java | 适配新字段 |
| 修改 | tui/Tui.java | ToolExecutor+UI展示 |
| 修改 | easycode.yaml.example | +新字段 |
| 新建 | test/.../ToolRegistryTest.java | 注册测试 |
| 新建 | test/.../EditFileToolTest.java | 匹配替换测试 |
| 新建 | test/.../ExecCommandToolTest.java | 超时+危险拦截测试 |
| 新建 | test/.../AnthropicProviderToolTest.java | SSE解析测试 |
| 新建 | test/.../ConversationMgrTrimTest.java | 窗口裁剪测试 |

## T1: 定义基础类型
**文件:** tool/Tool.java, tool/ToolResult.java, provider/ToolCall.java
**依赖:** 无
**验证:** mvn compile 通过

## T2: 定义 MessageBlock sealed interface
**文件:** conversation/MessageBlock.java, MessageRecord.java
**依赖:** T1
**验证:** mvn compile 通过

## T3: 实现 ToolRegistry
**文件:** tool/ToolRegistry.java
**依赖:** T1
**验证:** mvn compile 通过

## T4: 实现六个工具
**文件:** tool/ReadFileTool.java 等六文件
**依赖:** T1, T3
**验证:** mvn compile 通过；toToolsJson() 生成正确格式

## T5: Config 扩展
**文件:** config/Config.java, ConfigLoader.java, easycode.yaml.example
**依赖:** 无
**验证:** mvn compile 通过

## T6: StreamHandler + LlmProvider 接口扩展
**文件:** provider/StreamHandler.java, provider/LlmProvider.java
**依赖:** T1
**验证:** mvn compile 通过

## T7: AnthropicProvider 工具调用解析
**文件:** provider/AnthropicProvider.java
**依赖:** T1, T2, T5, T6
**验证:** mvn compile 通过

## T8: ConversationMgr 增强
**文件:** conversation/ConversationMgr.java
**依赖:** T2, T5
**验证:** mvn compile 通过

## T9: TUI 工具 UI + ToolExecutor
**文件:** tui/Tui.java
**依赖:** T3, T6, T8
**验证:** mvn compile 通过

## T10: Test 编写
**文件:** 5个测试类
**依赖:** T3, T4, T7, T8
**验证:** mvn test 全部通过

## 执行顺序
```
T1 → T2 → T3 → T4
  |        |
  └→ T6 → T7
  |
T5 → T8 → T9 → T10
```
