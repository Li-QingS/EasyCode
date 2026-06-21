 # Skill 系统 Tasks

 ## 文件清单

 | 操作 | 文件 | 职责 |
 |------|------|------|
 | 新建 | `src/main/java/com/easycode/skill/SkillFrontmatter.java` | 轻量元信息数据记录 + YAML 解析工厂方法 |
 | 新建 | `src/main/java/com/easycode/skill/SkillDef.java` | 完整 Skill 定义 + ToolDefinition 内部记录 |
 | 新建 | `src/main/java/com/easycode/skill/SkillLoader.java` | 三级目录扫描、优先级覆盖、启动校验 |
 | 新建 | `src/main/java/com/easycode/skill/SkillRegistry.java` | 运行时两阶段管理、激活/停用、白名单并集 |
 | 新建 | `src/main/java/com/easycode/skill/LoadSkillTool.java` | 系统级工具，Agent 调用触发加载和激活 |
 | 新建 | `src/main/java/com/easycode/skill/SkillExecutor.java` | inline / fork 两种执行模式 |
 | 修改 | `src/main/java/com/easycode/tool/ToolRegistry.java` | 新增白名单过滤版 toToolsJson |
 | 修改 | `src/main/java/com/easycode/prompt/Prompt.java` | 新增 buildWithSkills 填充已激活 Skill 槽位 |
 | 修改 | `src/main/java/com/easycode/command/CommandDispatcher.java` | 新增动态注册/注销 Skill 命令 |
 | 修改 | `src/main/java/com/easycode/agent/AgentLoop.java` | 取白名单、注入已激活 Skill 到 stablePrompt |
 | 修改 | `src/main/java/com/easycode/Main.java` | 初始化 Skill 系统各组件 |
 | 新建 | `src/main/resources/skills/commit.md` | 内置样板：生成 commit message |
 | 新建 | `src/main/resources/skills/review.md` | 内置样板：代码审查 |
 | 新建 | `src/main/resources/skills/test.md` | 内置样板：运行测试并修复 |

 ## T1: SkillFrontmatter 数据记录

 **文件：** `src/main/java/com/easycode/skill/SkillFrontmatter.java`（新建）
 **依赖：** 无
 **步骤：**
 1. 定义 record `SkillFrontmatter`，字段：`String name`, `String description`, `String mode`, `String context`, `String model`, `List<String> allowedTools`, `Path sourcePath`
 2. 紧凑构造器校验：name 不为空，description 为空时给 "(无描述)"，mode 默认 "inline"，context 默认 "none"，allowedTools 默认空列表
 3. 静态工厂 `fromYaml(String yamlBlock, Path source)`：用 `YAMLFactory` 解析，缺失可选字段给默认值

 **验证：** `mvn compile -pl .` 编译通过

 ## T2: SkillDef 完整定义

 **文件：** `src/main/java/com/easycode/skill/SkillDef.java`（新建）
 **依赖：** T1
 **步骤：**
 1. 定义 record `SkillDef`，字段：`SkillFrontmatter frontmatter`, `String promptBody`, `Map<String, ToolDefinition> tools`
 2. 内部定义 record `ToolDefinition`：`String name`, `String description`, `JsonNode inputSchema`, `Path scriptPath`

 **验证：** `mvn compile -pl .` 编译通过

 ## T3: SkillLoader 三级扫描与校验

 **文件：** `src/main/java/com/easycode/skill/SkillLoader.java`（新建）
 **依赖：** T1、`ToolRegistry`
 **步骤：**
 1. 构造函数接收 `ToolRegistry` 和 `Path projectRoot`
 2. `loadAll()` 方法：按内置（classpath）→ 用户 `~/.easycode/skills/` → 项目 `.easycode/skills/` 顺序扫描，用 `LinkedHashMap` 去重实现优先级覆盖
 3. 对每个 `.md` 文件：读文件，按 `---` 切 frontmatter 块，调 `fromYaml` 解析；失败则 `System.err` 记日志，跳过
 4. 对每个子目录：有 `SKILL.md` 则同上解析；解析 `tool.json` 得到工具定义列表
 5. `validateAllowedTools()`：遍历 frontmatter 的 `allowedTools`，在 ToolRegistry 中不存在则抛 `IllegalStateException`
 6. 内置 Skill 文件列表硬编码 `["commit.md", "review.md", "test.md"]`

 **验证：** `mvn compile -pl .` 编译通过

 ## T4: SkillRegistry 运行时管理

 **文件：** `src/main/java/com/easycode/skill/SkillRegistry.java`（新建）
 **依赖：** T1, T2, T3
 **步骤：**
 1. 字段：`Map<String, SkillFrontmatter> lightweight`，`Map<String, SkillDef> loaded`，`Set<String> activated`，`ToolRegistry tools`
 2. `initialize(List<SkillFrontmatter>)` 填充 lightweight 层
 3. `buildAvailableSkillsText()` 生成「可用 Skill: review — 代码审查, ...」文本
 4. `load(String name)` 读完整 `SKILL.md` 和 `tool.json`，构造 `SkillDef`，缓存到 loaded
 5. `activate(String name)` 加入 activated，目录型 Skill 注册专属工具
 6. `deactivate(String name)` 从 activated 移除，反注册专属工具
 7. `clearActivated()` 清空 activated
8.  `clearWhitelist()` 清空白名单但保持 Skill 激活状态
9. `getActivatedPrompt()` 返回所有 activated Skill 正文拼接
 10. 用 `synchronized` 保护所有读写操作

 **验证：** `mvn compile -pl .` 编译通过

 ## T5: ToolRegistry 白名单过滤

 **文件：** `src/main/java/com/easycode/tool/ToolRegistry.java`（修改）
 **依赖：** 无
 **步骤：**
 1. 新增 `toToolsJson(Set<String> allowedNames)` 方法
 2. 内部逻辑：`allowedNames` 为 null 或空 → 退化调用无参版；有内容 → 遍历 `tools`，只保留 name 在集合中的工具，注意系统级工具（`load_skill`）始终纳入
 3. 不修改现有 `toToolsJson()` 和 `toToolsJson(Permission)` 方法

 **验证：** `mvn compile -pl .` 编译通过

 ## T6: Prompt.buildWithSkills

 **文件：** `src/main/java/com/easycode/prompt/Prompt.java`（修改）
 **依赖：** T4
 **步骤：**
 1. 新增 `buildWithSkills(String instructions, String memory, String activatedSkills)` 方法
 2. 逻辑：复制 `buildSystemPrompt` 的逻辑，但将 `optionalModules()` 中 priority 90 的「已激活 Skill」槽位 content 设为 `activatedSkills`（非空时）
 3. 保持 `buildSystemPrompt` 不变，向下兼容

 **验证：** `mvn compile -pl .` 编译通过

 ## T7: LoadSkillTool 系统级工具

 **文件：** `src/main/java/com/easycode/skill/LoadSkillTool.java`（新建）
 **依赖：** T4
 **步骤：**
 1. 实现 `Tool` 接口，工具名 `"load_skill"`
 2. 参数 schema：`{type: object, properties: {name: {type: string}}, required: [name]}`
 3. `execute()` 逻辑：从 input 取 name → `registry.load(name)` → `registry.activate(name)` → 返回成功消息
 4. 如果目录型 Skill：将 `SkillDef.tools` 每个条目构造匿名 `Tool` 注册到 ToolRegistry
 5. `category()` 返回 `Tool.Category.SEARCH`，`permission()` 返回 `READ_ONLY`

 **验证：** `mvn compile -pl .` 编译通过

 ## T8: SkillExecutor inline / fork 执行

 **文件：** `src/main/java/com/easycode/skill/SkillExecutor.java`（新建）
 **依赖：** T4, T5
 **步骤：**
 1. `execute(SkillDef, String arguments, ConversationMgr, ToolRegistry, LlmProvider, Config)`
    - 判定 `frontmatter.mode()` 是 "inline" 还是 "fork"
 2. `inline()`：将 promptBody 中 `$ARGUMENTS` 字符串替换为 arguments（null 时替换为空），作为 `MessageRecord(Role.USER, ...)` 追加到 conv
 3. `fork()`：创建独立 `ConversationMgr`，按 context 策略构造首条上下文消息
    - `"full"`：调 `SummaryGenerator.summarize()`，摘要作为首条 context
    - `"recent"`：拷贝主对话最近 5 条 message
    - `"none"`：空
    - 若 allowedTools 非空则同上追加 SKILL_END 指令到 prompt 末尾；注入替换后的 Skill prompt 作为 user 消息
    - 创建过滤后 ToolRegistry（有 allowedTools 则只留白名单工具）
    - 若 Skill 指定 model，创建新 `LlmProvider`（同 baseUrl/apiKey，model 不同）
    - 新建 `AgentLoop`，跑完，取最终 assistant 文本
    - 结果作为 `MessageRecord(Role.ASSISTANT, result)` 追加回主 conv

 **验证：** `mvn compile -pl .` 编译通过

 ## T9: CommandDispatcher 动态命令注册

 **文件：** `src/main/java/com/easycode/command/CommandDispatcher.java`（修改）
 **依赖：** T4
 **步骤：**
 1. 新增 `registerSkillCommands(List<SkillFrontmatter>)` 方法
 2. 为每个 Skill 创建 `CommandDef`：
    - `name` = Skill name，`type` = `PROMPT`
    - `paramHint` = `"[参数]"`
    - handler：取 args → `registry.load(name)` → `registry.activate(name)` → `SkillExecutor.execute(..., args, ...)` → 返回 `CommandResult.Prompt(skillPrompt)`（inline 模式）或 `CommandResult.Message(result)`（fork 模式）
 3. 新增 `reloadSkillCommands(List<SkillFrontmatter>)` 方法：对比新旧列表，注册新增的，注销删除的
 4. 依赖注入 `SkillRegistry` 字段

 **验证：** `mvn compile -pl .` 编译通过

 ## T10: AgentLoop 集成（白名单 + Skill 注入）

 **文件：** `src/main/java/com/easycode/agent/AgentLoop.java`（修改）
 **依赖：** T4, T5, T6
 **步骤：**
 1. 新增 `SkillRegistry skillRegistry` 字段，通过构造函数注入（可为 null，向下兼容）
 2. 在 `run()` 的每轮循环中：
    - 从 `skillRegistry.activeToolWhitelist()` 取白名单
    - 白名单非空时调 `tools.toToolsJson(whitelist)` 过滤；否则调 `tools.toToolsJson()`
 3. 在 `run()` 返回前扫描最终文本：匹配到 `<!-- SKILL_END -->` 则调用 `skillRegistry.clearWhitelist()`
4. stablePrompt 改用 `Prompt.buildWithSkills(instructions, memory, skillRegistry.getActivatedPrompt())`

 **验证：** `mvn compile -pl .` 编译通过

 ## T11: Main.java 启动集成

 **文件：** `src/main/java/com/easycode/Main.java`（修改）
 **依赖：** T3, T4, T7, T9, T10
 **步骤：**
 1. 在 `ToolRegistry` 注册完内置工具和 MCP 工具后，创建 `SkillLoader`，调 `loadAll()` 和 `validateAllowedTools()`
 2. 创建 `SkillRegistry`，调 `initialize(frontmatters)`
 3. 创建 `LoadSkillTool(registry)` 并注册到 `ToolRegistry`
 4. 把 `buildAvailableSkillsText()` 追加到 instructions 尾部
 5. 把 `SkillRegistry` 传给 `AgentLoop` 构造函数
 6. 把 `SkillRegistry` 传给 `CommandDispatcher`，调用 `registerSkillCommands()`

 **验证：** `mvn compile -pl .` 编译通过

 ## T12: 内置样板 Skill

 **文件：** 三个新建
 **依赖：** T11
 **步骤：**
 1. `src/main/resources/skills/commit.md` — name: commit, description: 生成 commit message, mode: inline, allowedTools: [exec_command] + SOP 指令：执行 `git diff --staged`，分析后生成简洁中文 commit message
 2. `src/main/resources/skills/review.md` — name: review, description: 代码审查, mode: fork, context: full, allowedTools: [read_file, grep_code, exec_command] + SOP 指令：审查代码找 bug/安全隐患/性能问题/代码异味
 3. `src/main/resources/skills/test.md` — name: test, description: 运行测试并修复, mode: fork, context: none, allowedTools: [read_file, exec_command, write_file, edit_file, grep_code] + SOP 指令：运行项目测试，失败时分析失败用例并修改代码直到通过

 **验证：** `mvn compile -pl .` 编译通过

 
## T14: 热更新轮询线程

**文件：** `src/main/java/com/easycode/skill/SkillWatcher.java`（新建）
**依赖：** T4, T9
**步骤：**
1. 创建守护线程，每 2 秒检查 `.easycode/skills/` 和 `~/.easycode/skills/` 目录的 `lastModified`
2. 检测到变更后调 `SkillLoader.loadAll()` 获取新 frontmatter 列表
3. 调 `SkillRegistry.reload(fresh)` 刷新 lightweight 层和 loaded 缓存
4. 调 `CommandDispatcher.reloadSkillCommands(fresh)` 更新斜杠命令注册
5. 异常捕获：单次轮询失败不中断线程

**验证：** `mvn compile -pl .` 编译通过

## T13: 编译验证

 **依赖：** T1-T12, T14
 **步骤：**
 1. `mvn compile -pl .` 确认全部编译通过
 2. `mvn test -pl .` 确认现有测试不受影响

 **验证：** `mvn compile test -pl .` 输出 BUILD SUCCESS

 ## 执行顺序

 ```
 T1 ──→ T2 ──→ T3 ──→ T4 ──→ T7 ──→ T8
                  │            │
                  └──→ T5      └──→ T9
                        │            │
                        └──→ T6      │
                              │      │
                              └──→ T10 ←──┘
                                    │
                                    ▼
                                   T11
                                    │
                                    ▼
                         T4 ──→ T14 ──→ T9
                                   │
                                   ▼
                        T12 ──→ T13
 ```
