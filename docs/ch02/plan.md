<!-- 生成时间: 2026-06-17 -->
# EasyCode 纯对话阶段 Plan

## 架构概览

```
┌──────────────────────────────────────────────┐
│                  CLI Main                     │
│       加载配置 → 创建 Provider → 启动 TUI     │
└──────────────┬───────────────────────────────┘
               │
    ┌──────────┼──────────┐
    ▼          ▼          ▼
┌──────┐  ┌────────┐  ┌──────────┐
│Config│  │Provider│  │   TUI     │
│      │  │ Layer  │  │ (JLine)   │
│ YAML │  │        │  │           │
│→Obj  │  │LlmProv │  │ 输入循环   │
│      │  │ -Anthrop│  │ 流式渲染   │
└──────┘  │ -OpenAI│  │ 命令处理   │
          └───┬────┘  └─────┬─────┘
              │             │
              ▼             │
       ┌────────────┐       │
       │ HttpClient  │       │
       │ (JDK 内置)  │       │
       │ SSE 解析    │       │
       └────────────┘       │
              │             │
              ▼             ▼
       ┌──────────────────────┐
       │   ConversationMgr    │
       │   List<Message> 历史  │
       │   构建请求上下文      │
       └──────────────────────┘
```

**各组件职责：**

- **Config**——读取当前目录 `easycode.yaml`，Jackson 反序列化为配置对象，校验必填字段。
- **Provider 层**——`LlmProvider` 接口定义 `chatStream(messages, handler)` 方法。两个实现类分别对接 Anthropic Messages API 和 OpenAI Chat Completions API。
- **ConversationMgr**——管理会话消息历史（`List<Message>`），每次调用前组装完整上下文传给 Provider。
- **TUI**——JLine Terminal + LineReader 实现输入循环，收到 token 回调后实时打印，处理特殊命令。

## 核心数据结构

### Config

```java
public record Config(
    String protocol,   // "anthropic" | "openai"
    String model,      // e.g. "claude-sonnet-4-20250514", "gpt-4o"
    String baseUrl,    // API endpoint
    String apiKey      // authentication key
) {}
```

### Message

```java
public record Message(
    Role role,    // USER | ASSISTANT
    String content
) {}

public enum Role { USER, ASSISTANT }
```

### StreamHandler

```java
@FunctionalInterface
public interface StreamHandler {
    void onToken(String token);
    default void onComplete() {}
    default void onError(Exception e) {}
}
```

### LlmProvider

```java
public interface LlmProvider {
    void chatStream(List<Message> history, StreamHandler handler);
}
```

### ProviderFactory

```java
public final class ProviderFactory {
    public static LlmProvider create(Config config);
}
```

## 模块设计

### 模块 A：Config（配置加载）

**职责：** 读取 `easycode.yaml` → 反序列化为 `Config` → 校验。

**对外接口：** `ConfigLoader.load(String path)`

**依赖：** Jackson（ObjectMapper + YAMLFactory），无项目内部依赖。

### 模块 B：Provider 层

**职责：** 定义统一接口，封装 HTTP 请求构造、SSE 流解析、token 回调。

| 实现 | 请求 API | SSE 解析要点 |
|------|---------|-------------|
| AnthropicProvider | /v1/messages | data: 行 → JSON → delta.text / delta.thinking |
| OpenAIProvider | /v1/chat/completions | data: [DONE] 结束；data: 行 → JSON → choices[0].delta.content |

### 模块 C：ConversationMgr（会话管理）

**职责：** 维护 `List<Message>`，提供增删查方法。

```java
public final class ConversationMgr {
    public void addUserMessage(String content);
    public void addAssistantMessage(String content);
    public List<Message> getHistory();
}
```

### 模块 D：TUI（交互界面）

**职责：** JLine 输入循环、流式渲染、特殊命令处理。

```java
public final class Tui {
    public void start(LlmProvider provider);
}
```

## 模块交互

**启动流程：** Main → ConfigLoader.load() → ProviderFactory.create() → new Tui().start()

**单轮对话：** Tui 读取输入 → ConversationMgr.addUserMessage() → provider.chatStream(history, handler) → handler.onToken() → System.out.print()

**特殊命令：** `/exit` → break 主循环退出；`/help` → 打印命令列表

## 文件组织

```
EasyCode/
├── easycode.yaml.example
├── pom.xml
└── src/
    ├── main/java/com/easycode/
    │   ├── Main.java
    │   ├── config/
    │   │   └── ConfigLoader.java
    │   ├── provider/
    │   │   ├── LlmProvider.java
    │   │   ├── StreamHandler.java
    │   │   ├── ProviderFactory.java
    │   │   ├── AnthropicProvider.java
    │   │   └── OpenAIProvider.java
    │   ├── conversation/
    │   │   └── ConversationMgr.java
    │   └── tui/
    │       └── Tui.java
    └── test/java/com/easycode/
        ├── config/
        │   └── ConfigLoaderTest.java
        ├── provider/
        │   └── ProviderFactoryTest.java
        └── conversation/
            └── ConversationMgrTest.java
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| Java 版本 | 17 | pom.xml 已设定，LTS，内置 HttpClient |
| 构建 | Maven | 已有 pom.xml |
| TUI | JLine 3.x | 用户选定 |
| HTTP | java.net.http.HttpClient | JDK 内置，原生 SSE 支持 |
| JSON/YAML | Jackson | 一套 API 统一处理 |
| 配置格式 | YAML，四字段 | 用户指定 |
| 对话历史 | 内存 List<Message> | session 级别，不持久化 |
| 错误处理 | 消息提示 + 优雅退出 | 不暴露堆栈 |
| 测试 | JUnit 5 | Maven 标准 |

## 依赖清单

| GroupId | ArtifactId | 用途 |
|---------|-----------|------|
| org.jline | jline | TUI 终端交互 |
| com.fasterxml.jackson.core | jackson-databind | JSON 解析 |
| com.fasterxml.jackson.dataformat | jackson-dataformat-yaml | YAML 解析 |
| org.junit.jupiter | junit-jupiter | 测试（test scope） |
