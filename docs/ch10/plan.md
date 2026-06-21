 # Skill 系统 Plan

 ## 架构概览

 Skill 系统新增 6 个类到 `com.easycode.skill` 包，修改 5 个现有文件。

 **新增模块：**

 | 类 | 职责 |
 |---|---|
 | `SkillFrontmatter` | 数据记录：name、description、mode、context、model、allowedTools、sourcePath |
 | `SkillDef` | 完整 Skill 定义：frontmatter + promptBody + 可选 toolDefinitions + skillDir |
 | `SkillLoader` | 三级目录扫描、YAML frontmatter 解析、优先级覆盖、启动校验 |
 | `SkillRegistry` | 运行时两阶段管理：轻量列表 → 按需加载 → 激活/停用 → 白名单控制 |
 | `LoadSkillTool` | 系统级内置工具，Agent 调用后加载完整 Skill 指令和专属工具 |
 | `SkillExecutor` | 执行模式：inline 模式注入当前对话，fork 模式隔离执行并回流摘要 |

 **修改模块：**

 | 文件 | 改动点 |
 |---|---|
 | `Main.java` | 初始化 SkillLoader → SkillRegistry → 注册 LoadSkillTool → 注入可用 Skill 列表 |
 | `AgentLoop.java` | 从 SkillRegistry 取白名单工具集；将已激活 Skill 注入 stablePrompt |
 | `ToolRegistry.java` | 新增 `toToolsJson(Set<String> allowedNames)` 白名单过滤版 |
 | `Prompt.java` | 新增 `buildWithSkills(instructions, memory, activatedSkills)` 填充 priority 90 槽位 |
 | `CommandDispatcher.java` | 根据 SkillRegistry 自动注册/注销 Skill 对应的斜杠命令 |

 **数据流：**

 ```
 启动 → SkillLoader 扫描三级目录 → 生成 List<SkillFrontmatter>
      → SkillRegistry(lightweight) 持有
      → 注入到对话："可用 Skill: ..."
      → 注册斜杠命令: /commit, /review, /test
      → LoadSkillTool 注册到 ToolRegistry

 用户触发 Skill（显式 /name 或 Agent 自动判断）→
      → LoadSkillTool.execute("name")
      → SkillRegistry.load("name") 读取完整 SKILL.md + tool.json
      → SkillRegistry.activate("name")
      → 目录型: 注册专属工具到 ToolRegistry
      → SkillExecutor 按 mode 执行（inline 或 fork）
      → 下一轮 AgentLoop: 白名单过滤 toToolsJson()，stablePrompt 含已激活 Skill
 ```

 ## 核心数据结构

 ### SkillFrontmatter

 ```java
 /** 从 YAML frontmatter 解析的轻量元信息 */
 public record SkillFrontmatter(
     String name,              // 唯一标识，如 "review"
     String description,       // 一句话说明
     String mode,              // "inline" 或 "fork"
     String context,           // fork 模式上下文策略: "full" / "recent" / "none"
     String model,             // 可选指定模型，null 表示使用全局
     List<String> allowedTools,// 白名单工具名列表
     Path sourcePath           // 源文件路径，用于热更新和日志
 ) {
     /** 从单个文件或目录的 frontmatter 构建 */
     public static SkillFrontmatter fromYaml(String yamlBlock, Path source);
 }
 ```

 ### SkillDef

 ```java
 /** 完整 Skill 定义 = frontmatter + 正文 + 可选专属工具 */
 public record SkillDef(
     SkillFrontmatter frontmatter,
     String promptBody,              // Markdown 正文（SOP 指令）
     Map<String, ToolDefinition> tools  // 目录型 Skill 的专属工具，单文件 Skill 为空
 ) {
     public record ToolDefinition(
         String name,
         String description,
         JsonNode inputSchema,
         Path scriptPath    // 实现脚本路径
     ) {}
 }
 ```

 ### SkillRegistry（核心运行时）

 ```java
 public class SkillRegistry {
     // 轻量层：启动时填充，存储所有已发现的 Skill 的 frontmatter
     private Map<String, SkillFrontmatter> lightweight;

     // 完整层：按需加载后缓存，key 为 name
     private Map<String, SkillDef> loaded;

     // 激活层：已激活的 Skill name 集合，出现在稳定提示中
     private Set<String> activated;

     /** 返回所有轻量 Skill 的描述文本（注入对话用） */
     public String buildAvailableSkillsText();

     /** 按名加载完整 Skill（读 SKILL.md + tool.json） */
     public SkillDef load(String name);

     /** 激活指定 Skill，将其指令纳入稳定提示 */
     public void activate(String name);

     /** 停用指定 Skill */
     public void deactivate(String name);

     /** 清空所有已激活 Skill */
     public void clearActivated();

    /** 清空白名单但不影响已激活的 Skill（收到 SKILL_END 时调用） */
    public void clearWhitelist();
     /** 获取当前激活的 Skill 的白名单工具并集 */
     public Set<String> activeToolWhitelist();

     /** 热更新：重新扫描目录，更新 lightweight 层 */
     public List<String> reload(List<SkillFrontmatter> fresh);
 }
 ```

 ### LoadSkillTool

 ```java
 /** 系统级工具：Agent 调用后加载并激活 Skill，白名单永不限制它 */
 public class LoadSkillTool implements Tool {
     private final SkillRegistry registry;

     @Override public String name();       // "load_skill"
     @Override public String description();
     @Override public JsonNode inputSchema();  // {name: string}
     @Override public ToolResult execute(JsonNode input);
 }
 ```

 ### SkillExecutor

 ```java
 public class SkillExecutor {
     /** 执行已激活 Skill。mode 决定调用 inline() 或 fork() */
     public static String execute(SkillDef skill, String arguments,
                                   ConversationMgr conv, ToolRegistry tools,
                                   LlmProvider provider, Config config);

     /** inline 模式：替换 $ARGUMENTS，注入当前对话 */
     private static String inline(SkillDef skill, String arguments, ConversationMgr conv);

     /** fork 模式：独立对话，按策略带上下文，跑完回流 */
     private static String fork(SkillDef skill, String arguments,
                                 ConversationMgr conv, ToolRegistry tools,
                                 LlmProvider provider, Config config);

     /** 生成 fork 上下文：full → 摘要注入，recent → 最近 5 条，none → 空 */
     private static String buildForkContext(SkillDef skill, List<MessageRecord> history,
                                             LlmProvider provider);
 }
 ```

 ## 模块设计

 ### SkillLoader

 **职责：** 三级目录扫描、YAML frontmatter 解析、优先级覆盖、启动校验（F4, N1, N3）

 **对外接口：**
 - `List<SkillFrontmatter> loadAll()` — 扫描三个目录，解析每个 `.md` 文件和目录，返回去重后的轻量列表
 - 内部方法 `scanDir(Path dir)` — 扫描单个目录，识别单文件 Skill（`.md` 文件）和目录型 Skill（含 `SKILL.md` 的子目录）

 **依赖：** SnakeYAML（解析 frontmatter）、文件系统

 **解析逻辑：**
 1. 遍历三个目录（项目 `.easycode/skills/` → 用户 `~/.easycode/skills/` → 内置 `skills/`）
 2. 对每个 `.md` 文件：读取文件，按 `---` 切出 frontmatter 块，解析为 `SkillFrontmatter`，失败则日志记录并跳过（N1）
 3. 对每个子目录：如果存在 `SKILL.md`，同上解析 frontmatter，额外记录 `skillDir` 路径
 4. 同名覆盖：用 `LinkedHashMap` 按项目 > 用户 > 内置的加载顺序，后加载的替换先加载的
 5. 白名单校验：`SkillFrontmatter` 构建后，如果 `allowedTools` 非空，检查每个工具名是否在已注册的全局 `ToolRegistry` 中存在（预留校验接口），不存在则抛 `IllegalStateException`（N3）

 ### SkillRegistry

 **职责：** 运行时两阶段管理——持有轻量列表、按需加载完整定义、激活/停用控制、白名单并集计算（F5, F6, F9）

 **对外接口：**
 - `void initialize(List<SkillFrontmatter> frontmatters)` — 启动时填充轻量层
 - `String buildAvailableSkillsText()` — 生成注入对话的文本
 - `SkillDef load(String name)` — 按需加载完整 `SKILL.md` + `tool.json`，缓存到 loaded 层
 - `void activate(String name)` — 标记为激活，检查白名单工具存在性（运行时校验）
 - `void deactivate(String name)` — 反激活
- `void clearWhitelist()` — 清空白名单但保持 Skill 激活，恢复全局工具集
 - `void clearActivated()` — 清空全部激活（/clear 调用）
 - `Set<String> activeToolWhitelist()` — 返回所有激活 Skill 的 allowedTools 并集
 - `List<String> reload(List<SkillFrontmatter> fresh)` — 热更新

 **依赖：** `SkillLoader`、`ToolRegistry`

 ### LoadSkillTool

 **职责：** 系统级内置工具，Agent 调用后触发两阶段加载中的第二阶段（F5, F9）

 **工具名：** `load_skill`

 **参数：** `name`（string，必填）— Skill 唯一名

 **执行流程：**
 1. `registry.load(name)` 加载完整定义（若未加载）
 2. 如果是目录型 Skill：对 `SkillDef.tools` 每个条目，构造一个匿名 `Tool` 实现并注册到 `ToolRegistry`
 3. `registry.activate(name)` 标记激活
 4. 返回成功消息

 **特殊标记：** `requiresApproval()` 返回 false；系统级标记使白名单过滤始终放过此工具。

 ### SkillExecutor

 **职责：** 按 Skill 的 mode 执行——inline 注入当前对话，fork 隔离执行并回流（F8）

 **inline 流程：**
 1. 将 `SkillDef.promptBody` 中的 `$ARGUMENTS` 替换为用户参数（空字符串如果无参数）
 2. 替换后的文本作为 `MessageRecord(Role.USER, prompt)` 追加到 `ConversationMgr`
 3. 不启动新 AgentLoop——靠主对话的下一轮自动继续
4. 若 Skill 的 `allowedTools` 非空，自动在 prompt 末尾追加一行：「任务完成后请在回复末尾输出 `<!-- SKILL_END -->`」
 4. 返回值：prompt 文本

 **fork 流程：**
 1. 创建新的 `ConversationMgr` 实例
 2. 根据 `SkillFrontmatter.context` 策略：
    - `full`：调 `SummaryGenerator.summarize()` 将主对话压缩为摘要，作为首条 context 消息注入
    - `recent`：拷贝主对话最近 5 条 `MessageRecord` 到 fork 对话
    - `none`：空对话启动
 3. 将替换 `$ARGUMENTS` 后的 Skill prompt 作为首条 user 消息注入
7. fork 模式的 prompt 末尾同上追加 SKILL_END 指令
 4. 构造过滤后的 `ToolRegistry`：如果 `allowedTools` 非空，只保留白名单工具
 5. 确定 LLM 提供者：如果 Skill 指定了 `model`，创建新的 `LlmProvider`；否则复用全局
 6. 创建临时 `AgentLoop`，跑完整个循环
 7. 将最终 assistant 文本作为 `MessageRecord(Role.ASSISTANT, result)` 追加回主对话
 8. 返回值：生成的结果文本

 ## 模块交互

 ### 启动流程

 ```
 Main.main()
   ├── ConfigLoader.load()
   ├── ProviderFactory.create()
   ├── ToolRegistry.register(内置工具)
   ├── McpManager.discoverAndRegister()
   ├── InstructionLoader.load()
   ├── MemoryInjector.build()
   │
   ├── [new] SkillLoader.loadAll()              // 扫描三级目录 → List<SkillFrontmatter>
   ├── [new] SkillRegistry.initialize(frontmatters)
   ├── [new] LoadSkillTool 注册到 ToolRegistry
   ├── [new] SkillRegistry.buildAvailableSkillsText()
   │         → 注入到 instructions（追加在尾部）
   │
   ├── CommandRegistry + BuiltinCommands
   ├── [new] 为每个 Skill 注册斜杠命令
   │         → CommandDef.builder(name, args -> {
   │               registry.load(name);
   │               registry.activate(name);
   │               return SkillExecutor.execute(...)
   │           }).type(PROMPT)
   │
   ├── AgentLoop(..., instructions+skills_text, memoryIndex)
   └── Tui.start()
 ```

 ### 用户触发 Skill 流程

 ```
 用户输入 "/review 安全"
   │
   ▼
 CommandDispatcher.dispatch("/review 安全")
   │→ CommandRegistry.lookup("review") → CommandDef(handler)
   │→ handler.execute("安全")
   │
   ▼
 Skill 斜杠命令 handler:
   ├── SkillRegistry.load("review")
   ├── SkillRegistry.activate("review")
   │     └── [若目录型] 注册专属工具到 ToolRegistry
   ├── SkillExecutor.execute(skillDef, "安全", conv, tools, provider, config)
   │     ├── [inline]  替换 $ARGUMENTS → conv.addUserMessage()
   │     └── [fork]    创建临时 ConvMgr → 构建上下文 → 临时 AgentLoop → 结果回流
   └── CommandResult.Prompt(...) 或 CommandResult.Ok
 ```

 ### Agent Loop 运行时交互

 ```
 AgentLoop.run()
   ├── stablePrompt = Prompt.buildWithSkills(instructions, memory, activatedSkills)
   │     └── "已激活 Skill" slot = "## 已激活 Skill\n\n" + 各 Skill 正文
   │
   ├── for each round:
   │     ├── [new] Set<String> whitelist = SkillRegistry.activeToolWhitelist()
   │     ├── List<JsonNode> toolsJson = whitelist.isEmpty()
   │     │       ? tools.toToolsJson()
   │     │       : tools.toToolsJson(whitelist)
   │     │
   │     ├── Request(history, toolsJson, System(stablePrompt, env.render()), reminder)
   │     └── provider.chatStream() → toolCalls → ToolExecutor
  │
  ├── [new] 每轮结束后检查响应中是否包含 `<!-- SKILL_END -->`
  │     └── 如有 → skillRegistry.clearWhitelist()
   │
   └── (Skill 执行内联完成后，下一轮自动恢复全局工具)
 ```

 ### /clear 时清空 Skill

 ```
 Tui → /clear command → clearScreen()
   ├── SkillRegistry.clearActivated()
   └── 下一轮 stablePrompt 不再包含 Skill 正文
 ```

 ### 热更新流程

 ```
 后台线程 / 文件监听器
   │→ 检测到 .easycode/skills/ 中文件变更
   │→ SkillLoader.loadAll() → 新的 frontmatters 列表
   │→ SkillRegistry.reload(fresh)
   │     ├── 对比新旧：找出新增、删除、修改的 Skill
   │     ├── 刷新 lightweight 层
   │     ├── 重新注册/注销对应的斜杠命令
   │     └── 如果被修改的 Skill 已激活：刷新 loaded 缓存中的定义
 ```

 ## 文件组织

 ```
 src/main/java/com/easycode/
 ├── skill/
 │   ├── SkillFrontmatter.java       — 数据记录：name、description、mode、allowedTools 等
 │   ├── SkillDef.java               — 完整定义：frontmatter + promptBody + tools
 │   ├── SkillLoader.java            — 三级目录扫描、YAML 解析、优先级覆盖、启动校验
 │   ├── SkillRegistry.java          — 运行时管理：轻量→加载→激活，白名单并集
 │   ├── LoadSkillTool.java          — 系统级工具：实现 Tool 接口，触发第二阶段加载
 │   └── SkillExecutor.java          — 执行模式：inline 注入 / fork 隔离回流
 │
 ├── tool/
 │   └── ToolRegistry.java           — [修改] 新增 toToolsJson(Set<String> allowedNames)
 │
 ├── prompt/
 │   └── Prompt.java                 — [修改] 新增 buildWithSkills(instructions, memory, activatedSkills)
 │
 ├── agent/
 │   └── AgentLoop.java              — [修改] 取白名单、注入已激活 Skill 到 stablePrompt
 │
 ├── command/
 │   └── CommandDispatcher.java      — [修改] 根据 SkillRegistry 动态注册/注销命令
 │
 ├── Main.java                       — [修改] 初始化 Skill 系统各组件
 └── ...

 src/main/resources/
 └── skills/                         — 内置 Skill 目录（打包在 JAR）
     ├── commit.md                   — 生成 commit message
     ├── review.md                   — 代码审查
     └── test.md                     — 运行测试并修复
 ```

 ## 技术决策

 | 决策点 | 选择 | 理由 |
 |--------|------|------|
 | YAML 解析库 | SnakeYAML（已在 classpath） | pom.xml 依赖的 Jackson 已间接引入 SnakeYAML，无需新增依赖 |
 | frontmatter 解析 | 手动切 `---` 分隔符 + SnakeYAML | 比引入完整 Markdown frontmatter 库更轻，且结构简单可控 |
 | 三级目录扫描顺序 | 内置 → 用户 → 项目（用 LinkedHashMap 后加载覆盖） | 项目最高优先级，顺序加载自动实现覆盖 |
 | 白名单工具校验时机 | 启动时校验一次 + 激活时校验一次 | 启动时尽早发现配置错误，激活时防御热更新后工具注销 |
 | fork 模式 LLM Provider | 同 base_url / api_key，仅切换 model | 避免要求 Skill 携带独立密钥；model 差异可由 Skill 指定 |
 | 目录型 Skill 工具实现脚本执行方式 | `exec_command` 工具调用脚本 | 复用现有权限管线，不做特殊进程管理 |
 | fork 摘要生成 | 复用 `SummaryGenerator.summarize()` | 上下文管理已有成熟摘要能力，避免重复实现 |
 | 热更新检测 | 文件系统轮询（定期 `lastModified` 检查） | JLine 不支持插入文件监听事件；轮询间隔 2 秒，开销可忽略 |
 | 已激活 Skill 在系统提示中的格式 | `"## 已激活 Skill\n\n### Skill: {name}\n{description}\n\n{body}"` | Markdown 结构化，模型易解析；与现有 Prompt 模块风格一致 |
 | Skill 命令的 CommandType | `PROMPT`（复用现有类型） | handler 内部调用 SkillExecutor，结果以 Prompt 类型返回，Tui 已有处理逻辑 |
