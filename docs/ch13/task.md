# 子 Agent 系统 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `subagent/AgentDef.java` | Agent 定义 record |
| 新建 | `subagent/AgentDefLoader.java` | 三来源加载 |
| 新建 | `subagent/RunAgentTool.java` | 统一 run_agent 工具 |
| 新建 | `subagent/SubAgent.java` | 子 Agent 运行器 |
| 新建 | `subagent/TaskRecord.java` | 任务记录 |
| 新建 | `subagent/TaskManager.java` | 后台任务管理器 |
| 新建 | `resources/builtin/agents/reviewer.md` | 内置 reviewer Agent |
| 修改 | `Main.java` | 初始化 AgentDefLoader + TaskManager |
| 修改 | `agent/AgentLoop.java` | 暴露组件 getter |
| 新建 | `subagent/AgentDefLoaderTest.java` | 加载+覆盖测试 |
| 新建 | `subagent/RunAgentToolTest.java` | 工具路由测试 |
| 新建 | `subagent/SubAgentTest.java` | 子 Agent 执行测试 |
| 新建 | `subagent/TaskManagerTest.java` | 后台管理测试 |

## T1: 数据模型

**文件：** `subagent/AgentDef.java`, `subagent/TaskRecord.java`
**依赖：** 无
**步骤：** 1. 创建 AgentDef record（name/description/systemPrompt/toolsAllow/toolsDeny/model/maxTurns/permission，其中 maxTurns 默认 10）；2. 创建 TaskStatus 枚举 + TaskRecord record（id/agentName/status/output/turnsUsed/inputTokens/outputTokens/startTimeMs/endTimeMs）
**验证：** `mvn compile -q`

## T2: AgentDefLoader

**文件：** `subagent/AgentDefLoader.java`
**依赖：** T1
**步骤：** 1. loadAll(projectDir) 三来源加载；2. loadFromDir(dir) 扫描 *.md；3. parse(file) 解析 YAML frontmatter+正文；4. 同名覆盖（proj>user>builtin）
**验证：** `mvn compile -q`

## T3: 内置 Agent 定义

**文件：** `resources/builtin/agents/reviewer.md`
**依赖：** 无
**步骤：** 创建 reviewer 角色定义（tools_allow: read_file/grep_code，正文描述审查流程）
**验证：** AgentDefLoader 能加载到该内置定义

## T4: SubAgent + TaskManager

**文件：** `subagent/SubAgent.java`, `subagent/TaskManager.java`
**依赖：** T1
**步骤：** 1. SubAgent 实现 Callable<TaskRecord>；2. 构造独立 ConversationMgr；3. 构造内部 AgentLoop；4. 注入 seed messages+filteredTools；5. TaskManager 线程池+状态追踪+await
**验证：** `mvn compile -q`

## T5: RunAgentTool

**文件：** `subagent/RunAgentTool.java`
**依赖：** T1-T4
**步骤：** 1. 实现 Tool 接口；2. 解析 mode/name/prompt/background 参数；3. mode 分流；4. 构建 filteredTools（禁止 run_agent+角色 denylist）；5. submit → TaskManager；6. 同步/async 返回
**验证：** `mvn compile -q`

## T6: AgentLoop getter

**文件：** `agent/AgentLoop.java`
**依赖：** 无
**步骤：** 添加 getProvider()/getConfig()/getHookEngine() 三个 public getter
**验证：** 编译通过

## T7: Main.java 集成

**文件：** `Main.java`
**依赖：** T2, T4, T5
**步骤：** 1. 启动时 AgentDefLoader.loadAll()；2. 构造 TaskManager；3. 注册 RunAgentTool 到 ToolRegistry；4. RunAgentTool 注入 AgentDef Map + LlmProvider + HookEngine + TaskManager
**验证：** 启动日志显示 Agent 定义加载数量

## T8-T10: 测试

**T8 AgentDefLoaderTest：** 单目录加载+三来源覆盖+缺省值验证
**T9 RunAgentToolTest：** defined路由+fork强制后台+嵌套拦截
**T10 SubAgentTest+TaskManagerTest：** defined执行+fork继承+白黑名单+3并行+超时切后台

## 执行顺序

```
T1 → T2 → T4 → T5 → T6 → T7
  ↘ T3 ↗            ↘
                      T8 → T9 → T10
```
