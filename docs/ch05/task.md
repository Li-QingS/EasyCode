# 系统提示工程化 Tasks

## 文件清单
| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `prompt/Module.java` | Module record |
| 新建 | `prompt/Prompt.java` | 模块装配 |
| 新建 | `prompt/Environment.java` | 环境采集+渲染 |
| 新建 | `prompt/Reminder.java` | system-reminder |
| 新建 | `provider/Request.java` | Request record |
| 新建 | `provider/System.java` | System record |
| 修改 | `provider/StreamHandler.java` | onUsage 加缓存字段 |
| 修改 | `provider/LlmProvider.java` | chatStream(Request,StreamHandler) |
| 修改 | `provider/AnthropicProvider.java` | system数组+cache_control+reminder |
| 修改 | `provider/OpenAIProvider.java` | system前缀+reminder |
| 修改 | `agent/AgentLoop.java` | 环境采集/Request组装 |
| 修改 | `agent/AgentEvent.java` | TokenUsage加缓存字段 |
| 修改 | `agent/StreamingCollector.java` | onUsage同步 |
| 修改 | `tool/ExecCommandTool.java` | description强化 |
| 修改 | `tool/EditFileTool.java` | description强化 |
| 修改 | `tui/Tui.java` | AgentLoop构造传version |
| 修改 | `Main.java` | AgentLoop构造传version |
| 新建 | `test/.../PromptTest.java` | 装配测试 |
| 新建 | `test/.../EnvironmentTest.java` | 环境测试 |
| 新建 | `test/.../ReminderTest.java` | Reminder测试 |

## T1-T16 详见对话中 task.md 内容
执行顺序: T1→T2→T10→T12, T3-T5→T10, T5→T6/T7→T10, T8→T9→T10, T11并行, T13-T16测试
