# Hook 引擎 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `hook/HookEvent.java` | 事件枚举 |
| 新建 | `hook/HookContext.java` | 上下文 record |
| 新建 | `hook/ConditionNode.java` | 条件表达式 |
| 新建 | `hook/HookAction.java` | 动作接口 |
| 新建 | `hook/ShellAction.java` | Shell 动作 |
| 新建 | `hook/PromptAction.java` | 提示词动作 |
| 新建 | `hook/HttpAction.java` | HTTP 动作 |
| 新建 | `hook/SubAgentAction.java` | 子Agent 占位 |
| 新建 | `hook/HookRule.java` | 规则 record |
| 新建 | `hook/HookConfig.java` | YAML 加载+校验 |
| 新建 | `hook/HookEngine.java` | 调度引擎 |
| 修改 | `Main.java` | 加载 HookConfig |
| 修改 | `agent/AgentLoop.java` | 插入 fire() 调用 |
| 修改 | `agent/ToolExecutor.java` | PRE_TOOL 拦截 |
| 新建 | `hook/HookConfigTest.java` | 加载+校验测试 |
| 新建 | `hook/HookEngineTest.java` | 引擎测试 |
| 新建 | `hook/ConditionMatchTest.java` | 条件匹配测试 |
| 新建 | `hook/HookActionTest.java` | 动作测试 |

## T1: 数据模型

**文件：** `hook/HookEvent.java`, `hook/HookContext.java`, `hook/ConditionNode.java`, `hook/HookRule.java`
**依赖：** 无
**步骤：**
1. 创建 `HookEvent` 枚举：STARTUP, SHUTDOWN, SESSION_START, SESSION_END, TURN_START, TURN_END, PRE_LLM_REQUEST, POST_LLM_RESPONSE, PRE_TOOL, POST_TOOL
2. 创建 `HookContext` record（event, vars: Map<String,Object>）
3. 创建 `ConditionNode` sealed interface：Equals, NotEquals, Regex, Glob, All, Any
4. 创建 `HookRule` record（name, event, condition, action, once, async）
**验证：** `mvn compile -q` 通过

## T2: 动作接口 + ShellAction

**文件：** `hook/HookAction.java`, `hook/ShellAction.java`
**依赖：** T1
**步骤：**
1. 定义 `HookAction` 接口：`String execute(HookContext ctx) throws Exception` + `String type()`
2. 实现 `ShellAction`：command/cwd/env/timeout 字段，ProcessBuilder 执行，30s 默认超时，stdout+stderr 截断返回
**验证：** 单元测试执行 `echo hello`，输出为 `hello`

## T3: PromptAction + HttpAction + SubAgentAction

**文件：** `hook/PromptAction.java`, `hook/HttpAction.java`, `hook/SubAgentAction.java`
**依赖：** T2
**步骤：**
1. `PromptAction`：execute() 返回 text 字段内容
2. `HttpAction`：url/method/headers/body/timeout 字段，用 `java.net.http.HttpClient` 发请求，返回 `状态码:响应体截断`
3. `SubAgentAction`：execute() 返回 `[sub-agent not yet implemented]`
**验证：** 三个动作各写一个单元测试

## T4: HookConfig（YAML 加载+校验）

**文件：** `hook/HookConfig.java`
**依赖：** T1, T3
**步骤：**
1. 用 SnakeYAML 解析 `easycode.hooks.yaml`
2. 遍历每条规则校验：event 合法性、action.type 合法性、shell 必须有 command、http 必须有 url、pre-tool 禁止 async、all/any 子条件 ≥1
3. 校验失败 throw `IllegalStateException`（含字段+行号）
4. 无配置文件时返回空列表（不报错）
**验证：** 合法 YAML → 返回 List<HookRule>；非法 YAML → 抛异常且消息含具体错误字段

## T5: HookEngine 核心

**文件：** `hook/HookEngine.java`
**依赖：** T1, T3
**步骤：**
1. `fire(event, vars)` 方法——筛选匹配 event 类型的规则
2. 逐条调用 `matches(condition, vars)` 做条件匹配
3. 匹配成功则 `executeAction(rule, ctx)`
4. 每条规则 `try-catch` 包裹，异常记 `System.err` 不抛出
5. `once` 用 `Set<String>` 内存标记（存规则名）
6. `async` 提交到 `ExecutorService.newCachedThreadPool()`
7. 收集拦截结果：`pre-tool` 事件返回 `Optional<ToolResult>`
**验证：** 注册规则 → fire 事件 → 验证动作被执行

## T6: 条件匹配

**文件：** `HookEngine.matches()` 方法
**依赖：** T1
**步骤：**
1. 实现 `equals`/`not-equals`/`regex`/`glob` 四种匹配器
2. 实现 `all`（全部满足）/ `any`（任一满足）
3. `condition == null` → 无条件通过
**验证：** equals 匹配/不匹配、regex 匹配/不匹配、all 全通过/部分失败、any 任一通过/全失败

## T7: Main.java 集成

**文件：** `Main.java`
**依赖：** T4, T5
**步骤：**
1. 在 `ConfigLoader.load()` 之后调用 `HookConfig.load()` 加载规则
2. 构造 `HookEngine` 传入 `AgentLoop`
3. 启动后 `fire(STARTUP)`
4. shutdown hook 中 `fire(SHUTDOWN)`
**验证：** 启动日志中出现 Hook 加载信息

## T8: AgentLoop 集成

**文件：** `agent/AgentLoop.java`
**依赖：** T5
**步骤：**
1. 构造函数新增 `HookEngine` 参数
2. `run()` 中插入 `fire()` 调用点：
   - `run()` 开始 → `SESSION_START`
   - 每轮开始 → `TURN_START`
   - `Request` 构造前 → `PRE_LLM_REQUEST`
   - 收到响应后 → `POST_LLM_RESPONSE`
   - `run()` 结束 → `SESSION_END`
3. `PRE_TOOL` 和 `POST_TOOL` 在 ToolExecutor 中调用（T9）
**验证：** `mvn compile` 通过

## T9: ToolExecutor PRE_TOOL 拦截

**文件：** `agent/ToolExecutor.java`
**依赖：** T5
**步骤：**
1. `executeOneTool()` 中权限检查前调用 `hookEngine.fire(PRE_TOOL, vars)`
2. 若返回 `ToolResult`（非空），跳过工具执行，直接返回该结果
3. 工具执行后调用 `hookEngine.fire(POST_TOOL, vars)`
**验证：** 配置拦截规则后，对应工具调用返回 `isError=true`

## T10: HookConfig 测试

**文件：** `hook/HookConfigTest.java`
**依赖：** T4
**步骤：**
1. 合法 YAML 加载测试
2. 缺少 event → 校验失败
3. pre-tool + async=true → 校验失败
4. shell 无 command → 校验失败
5. http 无 url → 校验失败
6. 配置文件不存在 → 返回空列表
**验证：** `mvn test -Dtest=HookConfigTest`

## T11: HookEngine 测试

**文件：** `hook/HookEngineTest.java`
**依赖：** T5, T6
**步骤：**
1. 无条件规则触发测试
2. 条件匹配命中/未命中
3. once 规则第二次不触发
4. async 规则异步执行（sleep 1s 验证不阻塞）
5. pre-tool 拦截返回 ToolResult(isError=true)
6. 一条规则抛异常不影响后续规则
**验证：** `mvn test -Dtest=HookEngineTest`

## T12: 条件匹配 + 动作测试

**文件：** `hook/ConditionMatchTest.java`, `hook/HookActionTest.java`
**依赖：** T6, T3
**步骤：**
1. 四种匹配器各 2 个用例（匹配/不匹配）
2. all/any 组合各 2 个用例
3. Shell/Prompt/HTTP 动作各 1 个用例
4. SubAgent 占位动作验证
**验证：** `mvn test` 全部通过

## 执行顺序

```
T1 → T2 → T3 → T4 → T5
                    ↓
              T6 ──┤
                    ↓
              T7 → T8 → T9
                    ↓
              T10 → T11 → T12
```
