<!-- 生成时间: 2026-06-17 -->
# EasyCode 工具系统 Plan

## 架构概览

```
                            ┌─────────────────────┐
                            │        TUI           │
                            │  ┌───────────────┐   │
                            │  │  ToolExecutor  │   │
                            │  │  执行→展示→灌回 │   │
                            │  └───────┬───────┘   │
                            └──────────┼──────────┘
                                       │ onToolCall / onToken
                            ┌──────────┴──────────┐
                            │    LlmProvider       │
                            │  chatStream(msgs,    │
                            │    tools, handler)   │
                            │  ┌─ SSE 解析:        │
                            │  │  text_delta       │
                            │  │  tool_use block   │
                            │  │  usage            │
                            │  └───────────────────│
                            └──────────────────────┘
                                       │
                ┌──────────────────────┼──────────────────────┐
                ▼                      ▼                      ▼
        ┌──────────────┐    ┌─────────────────┐    ┌────────────────┐
        │  ToolRegistry │    │ ConversationMgr  │    │   Config       │
        │  Map<name,    │    │ List<Message>    │    │ contextWindow  │
        │       Tool>   │    │ token budget     │    │ toolTimeout    │
        │  →toolsJson() │    │ 窗口裁剪         │    │ systemPrompt   │
        └──────┬───────┘    └─────────────────┘    └────────────────┘
               │
    ┌──────────┼──────────┬──────────┬──────────┬──────────┐
    ▼          ▼          ▼          ▼          ▼          ▼
ReadFile   WriteFile  EditFile  ExecCmd   FindFiles  GrepCode
```

**新增/变更组件：**
- Tool 接口 + 六实现——统一契约，自描述 JSON Schema + 执行逻辑
- ToolRegistry——登记、按名查找、转 tools JSON 列表
- ToolCall / ToolResult——工具调用和结果的结构化类型
- ToolExecutor（TUI内）——接收 onToolCall→执行→展示→灌回→二次 chatStream
- LlmProvider 扩展——chatStream(messages, tools, handler)
- ConversationMgr 增强——block 消息结构 + token计数 + 窗口裁剪
- Config 扩展——contextWindow, toolTimeout, systemPrompt

## 核心数据结构

### Tool
```java
public interface Tool {
    String name();
    String description();
    JsonNode inputSchema();
    ToolResult execute(JsonNode input);
}
```

### ToolCall
```java
public record ToolCall(String id, String name, JsonNode input) {}
```

### ToolResult
```java
public record ToolResult(String toolName, boolean success, String content, long durationMs) {}
```

### MessageBlock (sealed interface)
```java
public sealed interface MessageBlock permits TextBlock, ToolUseBlock, ToolResultBlock {}
public record TextBlock(String text) implements MessageBlock {}
public record ToolUseBlock(String id, String name, JsonNode input) implements MessageBlock {}
public record ToolResultBlock(String toolUseId, String content, boolean isError) implements MessageBlock {}
```

### MessageRecord
```java
public record MessageRecord(Role role, String content, List<MessageBlock> blocks) {}
```

### StreamHandler 扩展
```java
default void onToolCall(ToolCall call) {}
default void onUsage(int inputTokens, int outputTokens) {}
```

### LlmProvider 扩展
```java
void chatStream(List<MessageRecord> history, List<JsonNode> tools, StreamHandler handler);
```

### Config 扩展
```java
private int contextWindow = 128_000;
private int toolTimeout = 30;
private String systemPrompt = "你是一个有帮助的 AI 助手。";
```

## 模块设计

### 模块 A: Tool 接口与六实现
- ReadFileTool: path→读文件，超10KB截断
- WriteFileTool: path+content→创建/覆盖
- EditFileTool: 唯一匹配替换；countMatches() 遍历
- ExecCommandTool: ProcessBuilder + 危险正则 + Future.get(timeout)
- FindFilesTool: Files.walk + glob matcher
- GrepCodeTool: Runtime.exec("rg ...")

### 模块 B: ToolRegistry
Map<String,Tool>，register/get/toToolsJson() 生成 Anthropic 格式 tools 数组

### 模块 C: ToolExecutor（TUI 内）
接收 onToolCall → registry.get.execute() → 展示状态 → 包装 block → 灌回 ConversationMgr → 二次 chatStream

### 模块 D: ConversationMgr 增强
estimateTokens() = 总字符数/3，trimToWindow(max) 保留首条+最新user

### 模块 E: AnthropicProvider 工具调用解析
content_block_start(tool_use) 记录 id+name → input_json_delta 拼接 → content_block_stop 组装 JsonNode → onToolCall

## 模块交互
用户输入 → trimToWindow → chatStream(history, tools, handler) → SSE → 
text_delta→onToken / tool_use→onToolCall→execute→灌回→chatStream→模型给出最终回答

## 文件组织
```
src/main/java/com/easycode/
├── config/Config.java              [改] +contextWindow/toolTimeout/systemPrompt
├── provider/
│   ├── LlmProvider.java            [改] +tools参数
│   ├── StreamHandler.java          [改] +onToolCall/onUsage
│   ├── ToolCall.java               [新]
│   ├── AnthropicProvider.java      [改] tool_use SSE解析+usage
│   └── OpenAIProvider.java         [改] 签名对齐
├── tool/
│   ├── Tool.java                   [新] 接口
│   ├── ToolResult.java             [新]
│   ├── ToolRegistry.java           [新]
│   └── *Tool.java (6个实现)        [新]
├── conversation/
│   ├── MessageBlock.java           [新] sealed interface
│   ├── MessageRecord.java          [改] +blocks
│   └── ConversationMgr.java        [改] +token估算+裁剪
└── tui/Tui.java                    [改] +ToolExecutor+UI展示
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 工具接口 | Tool 含 JSON Schema | 用户选定，纯Java |
| Token 估算 | 字符数/3 | 中英文近似，够用 |
| 窗口裁剪 | 从旧删到新，保留首条+最新user | 用户确认 |
| 工具超时 | Future.get(timeout) | JDK内置 |
| 危险命令 | 正则黑名单 | 简单有效 |
| SSE 工具解析 | content_block_start + input_json_delta 拼接 | Anthropic 标准 |
| 工具灌回 | TUI内同步执行+二次chatStream | 当前不做Agent Loop |
| MessageRecord | role+content+List<MessageBlock> | 统一结构 |
| 向后兼容 | Jackson默认值+@JsonProperty | 旧yaml不改也能跑 |
