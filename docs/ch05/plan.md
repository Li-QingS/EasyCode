# 系统提示工程化 Plan

## 架构概览
ch05 在 ch04 Agent Loop 之上叠加三层：

```
prompt 包 ──→ 模块化装配 + 环境采集 + Reminder 构造
 agent 包 ──→ 每轮组装 llm.Request（system 两段 + reminder）
  llm 包 ──→ AnthropicProvider：stable 打缓存断点，env 不打
             OpenAIProvider：stable 放 system 前缀
```

## 核心数据结构

### Module
```java
public record Module(String name, int priority, String content) {}
```

### Environment
```java
public record Environment(String workingDir, String platform, String date,
    String gitStatus, String shell, String appVersion, String model) {}
```

### Request / System
```java
public record Request(List<MessageRecord> messages, List<JsonNode> tools,
    System system, String reminder) {}
public record System(String stable, String environment) {}
```

## 文件组织
```
src/main/java/com/easycode/
├── prompt/
│   ├── Module.java           — 新增
│   ├── Prompt.java           — 新增: fixedModules/optionalModules/assemble/buildStable
│   ├── Environment.java      — 新增: Environment record + collect/render
│   └── Reminder.java         — 新增: wrap/planReminder/EXECUTE_DIRECTIVE
├── provider/
│   ├── LlmProvider.java      — 改: chatStream(Request, StreamHandler)
│   ├── Request.java          — 新增
│   ├── System.java           — 新增
│   ├── StreamHandler.java    — 改: onUsage 加 cacheWrite/cacheRead
│   ├── AnthropicProvider.java — 改: system 数组结构、reminder 织入、缓存解析
│   └── OpenAIProvider.java   — 改: system 前缀、reminder 注入、cached_tokens 解析
├── agent/
│   └── AgentLoop.java        — 改: 环境采集/system装配/reminder按轮次/Request组装
├── tool/
│   ├── ExecCommandTool.java  — 改: description 补强化
│   └── EditFileTool.java     — 改: description 补强化
```

## 技术决策
| 决策点 | 选择 | 理由 |
|--------|------|------|
| SDK 依赖 | 零新依赖，手工 JSON | 保持轻量 |
| Anthropic system | 两个 text content block，第一块尾带 cache_control | cache_control 断点只支持 content block 级别 |
| Reminder 织入 | 追加到最后一条 user 消息的 content 块 | 不破坏 role 交替 |
| Plan Mode 提醒 | 从 system 后缀迁移到 Reminder | system 只装稳定内容 |
