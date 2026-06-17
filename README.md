# EasyCode

终端 AI 编程助手——类似 Claude Code，Java 实现。

## 技术栈

- Java 17 + Maven
- JLine 3.x（终端交互）
- Jackson（JSON / YAML 解析）
- JDK 内置 HttpClient（SSE 流式消费）
- JUnit 5（单元测试）

## 当前功能

### 流式对话
- 支持 Anthropic 协议（含 DeepSeek 兼容 API）
- SSE 逐 token 输出，带 spinner 动画和耗时显示
- Markdown→ANSI 渲染（粗体、标题）
- 多轮对话 + 上下文窗口管理

### 工具系统

| 工具 | 权限 | 确认 | 功能 |
|------|------|------|------|
| `read_file` | 只读 | 否 | 读取文件内容 |
| `find_files` | 只读 | 否 | glob 模式查找文件 |
| `grep_code` | 只读 | 否 | 正则搜索代码内容 |
| `write_file` | 读写 | 是 | 创建/覆盖文件 |
| `edit_file` | 读写 | 是 | 唯一匹配替换 |
| `exec_command` | 读写 | 是 | 执行 shell 命令 |

- 统一 `Tool` 接口（name / description / inputSchema / execute）
- 权限分级：只读自动执行，读写工具终端 `[y/n]` 确认
- 超时保护（默认 30s）+ 危险命令拦截
- 工具结果回灌对话历史，模型据此给出最终回答

## 配置

项目根目录下的 `easycode.yaml`（参考 `easycode.yaml.example`）：

```yaml
protocol: anthropic
model: deepseek-v4-flash
base_url: https://api.deepseek.com/anthropic
api_key: sk-xxx

# 可选字段（省略用默认值）
context_window: 128000   # 上下文窗口 token 上限
tool_timeout: 30         # 工具执行超时秒数
system_prompt: "..."     # 自定义 system prompt
```

## 构建与运行

```bash
# 编译
cd "/mnt/d/agent project/EasyCode"
mvn compile

# 测试（23 个用例）
mvn test

# 启动（WSL）
java -cp "target/classes:$(cat /tmp/cp.txt)" com.easycode.Main

# 打包 fat JAR（Windows 直接 java -jar）
mvn package -DskipTests
```

## 项目结构

```
EasyCode/
├── pom.xml
├── easycode.yaml.example
├── CODEX.md                    # 项目上下文配置
├── 问题及解决方法.md             # 开发问题记录
└── src/
    ├── main/java/com/easycode/
    │   ├── Main.java           # 入口
    │   ├── config/             # Config / ConfigLoader
    │   ├── provider/           # LlmProvider / AnthropicProvider / StreamHandler
    │   ├── tool/               # Tool 接口 + 6 个实现 + ToolRegistry
    │   ├── conversation/       # MessageRecord / ConversationMgr
    │   └── tui/                # Tui / MarkdownRenderer
    └── test/java/com/easycode/
        ├── config/             # ConfigLoaderTest
        ├── conversation/       # ConversationMgrTest / ConversationMgrTrimTest
        ├── provider/           # ProviderFactoryTest
        └── tool/               # ToolRegistryTest / EditFileToolTest / ExecCommandToolTest
```

## 架构

```
用户输入 → TUI (JLine)
              │
              ▼
         ConversationMgr (窗口裁剪)
              │
              ▼
         LlmProvider (AnthropicProvider)
              │  SSE 流式消费
              │  ├─ text_delta → onToken → 逐字打印
              │  ├─ tool_use → onToolCall → 执行工具 → 灌回历史
              │  └─ usage → onUsage
              │
              ▼
         ToolRegistry → Tool.execute()
```
