 # Skill 系统 Checklist

 > 每一项通过运行代码或观察行为来验证，聚焦系统行为。

 ## 实现完整性
 - [ ] SkillFrontmatter 类已创建，`fromYaml` 方法可解析合法 YAML（验证：编译通过）
 - [ ] SkillDef 类已创建，包含 ToolDefinition 内部记录（验证：编译通过）
 - [ ] SkillLoader 三级扫描已实现，同名 Skill 按优先级覆盖（验证：编译通过）
 - [ ] SkillRegistry 运行时管理已实现：load、activate、deactivate、clear、whitelist（验证：编译通过）
 - [ ] ToolRegistry.toToolsJson(Set&lt;String&gt;) 白名单过滤已实现（验证：编译通过）
 - [ ] Prompt.buildWithSkills 已实现，激活 Skill 填入 priority 90 槽位（验证：编译通过）
 - [ ] LoadSkillTool 已注册，Agent 可调用（验证：编译通过）
 - [ ] SkillExecutor inline/fork 已实现，含 SKILL_END 指令注入（验证：编译通过）
 - [ ] CommandDispatcher.registerSkillCommands 已实现（验证：编译通过）
 - [ ] AgentLoop 白名单过滤和 Skill 注入已集成，含 SKILL_END 检测（验证：编译通过）
 - [ ] Main.java 启动流程已集成 Skill 系统（验证：编译通过）
 - [ ] 三个内置 Skill（commit/review/test）已创建（验证：编译通过）
 - [ ] SkillWatcher 热更新轮询线程已实现（验证：编译通过）

 ## spec 验收标准对照
 - [ ] AC1: `.easycode/skills/review.md` 放入后 `/help` 显示 `/review` 命令（验证：放入文件后启动 EasyCode，输入 `/help`，观察输出是否包含 `/review`）
 - [ ] AC2: `/review 重点关注安全问题` → prompt 中 `$ARGUMENTS` 被替换，执行结果为代码审查意见（验证：输入命令，观察 TUI 输出）
 - [ ] AC3: 项目级 Skill 覆盖用户级同名 Skill（验证：在两级各放一个不同描述的 `review.md`，启动后 `/help` 看生效的是项目级的描述）
 - [ ] AC4: `allowedTools` 含不存在工具名时启动报错（验证：修改某个 Skill 的 frontmatter 添加 `unknown_tool`，启动观察错误信息）
 - [ ] AC5: 损坏的 frontmatter 文件被跳过，其他 Skill 正常（验证：放入一个 `---` 后面跟非 YAML 的 `.md` 文件，启动观察日志和命令列表）
 - [ ] AC6: fork 模式不泄露中间工具调用到主对话（验证：激活一个有工具调用的 fork Skill，观察主对话终端的消息列表）
 - [ ] AC7: 白名单过滤：Skill 执行期间只看到指定工具；Agent 输出 `&lt;!-- SKILL_END --&gt;` 后下一轮恢复全局工具集（验证：用白名单 `[read_file]` 的 Skill，后续对话确认 exec_command 等恢复可用）
 - [ ] AC8: 目录型 Skill 专属工具可被调用（验证：放入含 `parse_resume.py` 的目录，执行后确认工具被注册并可调用）
 - [ ] AC9: 多 Skill 同时激活，俩 Skill 正文都在系统提示中（验证：先后激活 review 和 commit，用 session 记录检查稳定提示内容）
 - [ ] AC10: `/clear` 后已激活 Skill 清空（验证：激活一个 Skill → `/clear` → 下一轮对话确认 Skill 指令不在提示中）

 ## 集成
 - [ ] SkillLoader → SkillRegistry → LoadSkillTool → AgentLoop 链路完整（验证：编译 + 启动无异常）
 - [ ] SkillRegistry → CommandDispatcher 命令注册链路完整（验证：启动后 `/help` 列出 Skill 命令）
 - [ ] 热更新：修改 Skill 文件后命令列表更新（验证：启动后修改文件，等待 2 秒轮询间隔，`/help` 确认变化）

 ## 编译与测试
 - [ ] `mvn compile -pl .` 编译无错误
 - [ ] `mvn test -pl .` 现有测试全部通过
 - [ ] 无新增编译警告

 ## 端到端场景
 - [ ] 场景 1：用户放入自定义 `review.md` → 启动 EasyCode → `/help` 看到 `/review` → `/review 安全` → Agent 执行代码审查并输出结果
 - [ ] 场景 2：用户放入 frontmatter 损坏的 Skill → 启动不崩溃 → 其他合法 Skill 正常可用
 - [ ] 场景 3：激活 Skill 后 `/clear` → 输入新对话 → Skill 不再干预 → 需重新激活
 - [ ] 场景 4：fork 模式 Skill → 执行过程中主对话无中间调用痕迹 → 执行完毕后收到回流结果
 - [ ] 场景 5：inline 模式 Skill 带白名单 → Skill 执行 → Agent 输出 `&lt;!-- SKILL_END --&gt;` → 下一轮全局工具恢复
