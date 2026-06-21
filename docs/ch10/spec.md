 # Skill 系统 Spec

 ## 背景

 EasyCode 当前有两类可复用指令机制：`InstructionLoader` 加载的项目级/用户级 Markdown 自定义指令（`EasyCode.md`）、以及 `BuiltinCommands` 中的 `/review` 等预设提示词命令。两者各有限制——自定义指令每次对话都全量注入，无法按需激活；预设提示词命令没有元信息管理、没有工具裁剪、不能打包专属工具。

 用户经常需要在不同场景重复输入相似的引导词（如"帮我做代码审查""帮我写 commit message""帮我运行测试并修复"），每次都重新描述上下文和约束既低效又分散了核心对话的注意力。

 ## 目标

 构建一个 **Skill 系统**，把可复用的 AI 操作封装成带有 YAML 元信息的 Markdown 文件，支持两阶段加载（启动时轻量注入名称和说明，使用时按需加载完整指令和专属工具），支持两种执行模式（共享当前对话的 inline 模式和独立隔离对话的 fork 模式），并自动注册为斜杠命令。

 - 让团队沉淀和分发可复用的 AI 操作 SOP
 - 按需激活 Skill 而非全量注入，节省上下文
 - 工具白名单收窄选择面，提升模型选工具的准确率
 - 目录型 Skill 可自带专属工具，整套能力可移植

 ## 功能需求

 - **F1: Skill 定义格式** — 单个 Skill 使用 YAML frontmatter + Markdown 正文描述。frontmatter 包含唯一名字、一句话说明、可见工具白名单、执行模式（inline / fork）、fork 模式上下文携带策略（full / recent / none）、可选指定模型。正文是发送给 LLM 的 SOP 指令，支持 `$ARGUMENTS` 占位符。
 - **F2: 单文件 Skill** — 纯 `.md` 文件，仅包含 frontmatter + Markdown 正文。不包含 `tool.json` 或实现脚本，不能声明专属新工具，只能使用 EasyCode 已有的内置工具和 MCP 工具。
 - **F3: 目录型 Skill（含专属工具）** — 一个目录，内部至少包含入口 `SKILL.md`。与 F2 的区别在于可以自带专属新工具：通过 `tool.json` 声明新工具（name / description / inputSchema），配合实现脚本完成工具逻辑。整套目录自包含、可打包移植。`tool.json` 只负责注册新工具，`allowedTools` 只负责控制可见性——已存在的内置工具不得在 `tool.json` 中重复定义。
 - **F4: 三级存放与优先级覆盖** — 三级路径：项目 `.easycode/skills/` > 用户 `~/.easycode/skills/` > 内置（打包在 JAR 内）。同名 Skill 按优先级覆盖，高层覆盖低层。解析失败的单个文件静默跳过，不阻断整体。
 - **F5: 两阶段加载** — 启动时扫描全部 Skill 目录，仅解析 frontmatter 提取 name 和 description（轻量），注入对话让 Agent 知晓可用 Skill。使用时 Agent 调用 LoadSkill 工具按需加载完整 `SKILL.md` 正文和专属工具。
 - **F6: 激活后持久存在** — 已加载的 Skill 完整指令钉在环境上下文的"已激活 Skill"区域内（复用 `Prompt` 中预留的 priority 90 槽位），每轮重建系统提示时都在。多个 Skill 可同时激活。清空对话时清掉所有已激活 Skill。
 - **F7: 自动注册斜杠命令** — 启动时自动将 Skill 注册为斜杠命令（`/{skill-name}`），支持显式调用。支持热更新：检测到 Skill 文件变更后重新加载并更新命令注册。
 - **F8: 两种执行模式** — `inline` 模式将 `$ARGUMENTS` 替换后的 Skill prompt 作为当前对话的一条 user 消息注入，结果留在主对话历史中。`fork` 模式创建独立 `ConversationMgr`，按 Skill 指定上下文策略（full 摘要注入 / recent 最近 5 条 / none 不带）决定是否携带上下文，用 Skill 指定模型和过滤后工具集跑完 Agent Loop，结果作为 assistant 消息塞回主对话。
 - **F9: 工具白名单** — Skill 激活时，仅 `allowedTools` 列出的工具对当前请求可见（收窄 `toToolsJson()` 输出）。Skill 的 SOP 指令中自动注入结束标记规则，Agent 在完成 Skill 任务后输出 `<!-- SKILL_END -->`，系统检测到后恢复全局工具集。`LoadSkill` 工具为系统级，不受白名单约束。启动时白名单中出现不存在的工具名立即报错。
 - **F10: 内置样板 Skill** — 内置 `commit`（生成 commit message）、`review`（代码审查）、`test`（运行测试并修复）三个 Skill。

 ## 非功能需求

 - **N1: 解析鲁棒性** — 单个 Skill 文件的 YAML frontmatter 解析失败或格式不符合规范时，该文件静默跳过，记录日志但不阻断其他 Skill 的加载和应用启动。
 - **N2: 同名覆盖语义** — 同名 Skill 按项目 > 用户 > 内置的优先级覆盖。覆盖是完全替换（而非合并字段），以便团队在项目级彻底定制某个内置 Skill。
 - **N3: 白名单启动校验** — 启动扫描时，如果某个 Skill 的 `allowedTools` 中包含当前 `ToolRegistry` 中不存在的工具名，立即抛出明确的启动错误，指出是哪个 Skill、哪个工具名不存在。
 - **N4: 线程安全** — Skill 加载、激活/停用、命令注册/注销操作是线程安全的，不与 Agent Loop 的并发执行冲突。
 - **N5: 热更新响应性** — 通过文件系统监听或轮询检测 Skill 文件变更，在合理延迟内完成重载和命令更新。
 - **N6: 向后兼容** — 没有 skills 目录或目录为空时，启动不报错，行为与当前版本一致。

 ## 不做的事

 - Skill 的市场分发和版本管理（marketplace、版本号约束、依赖声明），留给后续章节
 - Skill 之间的依赖关系（如 Skill A 依赖 Skill B）
 - 启动时根据对话内容自动匹配并加载 Skill（F5 已支持 Agent 判断后调用 LoadSkill，但不做自动预加载）
 - 对 Skill 正文的语法校验（如检查占位符引用完整性、Markdown 结构合法性），只做 YAML frontmatter 的格式校验
 - 为 Skill 提供沙箱执行环境（目录型 Skill 的实现脚本在本地直接执行，权限由现有 `PermissionPipeline` 管控）

 ## 验收标准

 - **AC1:** 在 `.easycode/skills/` 放入一个合法的 `review.md` Skill 文件，启动 EasyCode 后 `/help` 能列出 `/review` 命令及其描述。
 - **AC2:** 输入 `/review 重点关注安全问题`，Agent 收到的 prompt 中 `$ARGUMENTS` 被替换为 `重点关注安全问题`，且执行结果为一条完整的代码审查意见。
 - **AC3:** 同时放入项目级 `~/.easycode/skills/review.md` 和 `.easycode/skills/review.md` 两个同名 Skill，最终生效的是项目级的描述和正文（覆盖验证）。
 - **AC4:** 故意在 `allowedTools` 中填写一个不存在的工具名，启动时报错并指出具体 Skill 名和工具名。
 - **AC5:** 放入一个 frontmatter 格式损坏的 `.md` 文件，启动日志记录跳过该文件，但不影响其他合法 Skill 加载。
 - **AC6:** 激活一个 `mode: fork` 的 Skill，执行过程中不会在主对话历史中出现中间轮次的工具调用记录，执行完成后回流一条 assistant 消息。
 - **AC7:** 激活一个有 `allowedTools` 白名单的 Skill，Agent 在 Skill 执行期间只能看到白名单内的工具；Agent 输出 `<!-- SKILL_END -->` 后下一轮自动恢复全局工具集。
 - **AC8:** 放入一个目录型 Skill（含 `SKILL.md` + `tool.json` + 实现脚本），执行时能调用专属工具并产生正确结果。
 - **AC9:** 多个 Skill 先后激活（先 `/review` 再 `/commit`），两个 Skill 的完整指令同时出现在系统提示的"已激活 Skill"区域。
 - **AC10:** `/clear` 清空对话后，之前激活的 Skill 不再出现在系统提示中，需重新激活。
