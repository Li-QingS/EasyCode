package com.easycode.command;

import java.util.List;
import java.util.Optional;

/**
 * 命令分流器：在用户输入端判断是命令还是对话，命令走本地分发，非命令交给 AI。
 *
 * <p>返回 {@code Optional.empty()} 表示不是命令，应由 AI 处理；
 * 否则返回命令执行结果。
 */
public final class CommandDispatcher {
    private final CommandRegistry registry;
    private UiController ui;
    private com.easycode.subagent.TaskManager taskManager;

    public CommandDispatcher(CommandRegistry registry, UiController ui) {
        this.registry = registry;
        this.ui = ui;
    }

    /** 注册全部内置命令并锁定注册表 */
        public void setUi(UiController ui) { this.ui = ui; }

    private com.easycode.subagent.WorktreeManager worktreeManager;
    public void setWorktreeManager(com.easycode.subagent.WorktreeManager wm) { this.worktreeManager = wm; }
    public void setTaskManager(com.easycode.subagent.TaskManager tm) { this.taskManager = tm; }
    // ====== Skill 命令注册（T9） ======

        private com.easycode.skill.SkillRegistry skillRegistry;
    private com.easycode.conversation.ConversationMgr skillConv;
    private com.easycode.tool.ToolRegistry skillTools;
    private com.easycode.provider.LlmProvider skillProvider;
    private com.easycode.config.Config skillConfig;

        public void setSkillRegistry(com.easycode.skill.SkillRegistry sr) {
        this.skillRegistry = sr;
    }

    /** 注入 Skill 执行所需的运行时对象 */
    public void setSkillContext(com.easycode.conversation.ConversationMgr conv,
                                 com.easycode.tool.ToolRegistry tools,
                                 com.easycode.provider.LlmProvider provider,
                                 com.easycode.config.Config config) {
        this.skillConv = conv;
        this.skillTools = tools;
        this.skillProvider = provider;
        this.skillConfig = config;
    }

    /** 为所有 Skill 注册斜杠命令 */
    public void registerSkillCommands() {
        if (skillRegistry == null) return;
        for (com.easycode.skill.SkillFrontmatter fm : skillRegistry.listAll()) {
            registerOneSkill(fm);
        }
    }

    private void registerOneSkill(com.easycode.skill.SkillFrontmatter fm) {
        CommandDef def = CommandDef.builder(fm.name(), args -> {
            try {
                com.easycode.skill.SkillDef sd = skillRegistry.load(fm.name());
                skillRegistry.activate(fm.name());
                String result = com.easycode.skill.SkillExecutor.execute(
                    sd, args, skillConv, skillTools, skillProvider, skillConfig);
                if ("fork".equals(fm.mode())) {
                    skillRegistry.deactivate(fm.name());
                    return new CommandResult.Message(result);
                }
                return new CommandResult.Prompt(result);
            } catch (Exception e) {
                return new CommandResult.Error("Skill 执行失败: " + e.getMessage());
            }
        })
            .description(fm.description())
            .paramHint("[参数]")
            .type(CommandType.PROMPT)
            .build();
        registry.register(def);
    }

    /** 热更新 Skill 命令：重新扫描并更新注册 */
    public void reloadSkillCommands(java.util.List<com.easycode.skill.SkillFrontmatter> fresh) {
        skillRegistry.reload(fresh);
        // 简化：重新注册所有 Skill 命令（同名覆盖由 CommandRegistry 的异常处理？不，我们跳过已存在的）
        for (com.easycode.skill.SkillFrontmatter fm : fresh) {
            if (registry.lookup(fm.name()) == null) {
                registerOneSkill(fm);
            }
        }
    }
    public void registerBuiltins() {
        List<CommandDef> builtins = BuiltinCommands.all(ui, registry, taskManager, worktreeManager);
        registry.registerAll(builtins);
        registry.seal();
    }

    /**
     * 分流入口：解析输入并分发到命令或返回空。
     *
     * @param input 用户原始输入
     * @return Optional.empty() 表示非命令输入，应该交给 AI；
     *         Optional.of(result) 表示已由命令系统处理
     */
    public Optional<CommandResult> dispatch(String input) {
        CommandParser.Parsed parsed = CommandParser.parse(input);
        if (!parsed.isValid()) return Optional.empty();

        // 特殊：/exit 和 /quit
        if (parsed.name().equals("exit") || parsed.name().equals("quit")) {
            return Optional.of(new CommandResult.Exit());
        }

        CommandDef def = registry.lookup(parsed.name());
        if (def == null) {
            return Optional.of(new CommandResult.NotFound(parsed.name()));
        }

        try {
            CommandResult result = def.handler().apply(parsed.args());
            return Optional.of(result);
        } catch (Exception e) {
            return Optional.of(new CommandResult.Error("命令执行异常: " + e.getMessage()));
        }
    }

    /** 生成 Tab 补全候选列表 */
    public List<CommandRegistry.Candidate> complete(String partial) {
        return registry.complete(partial);
    }

    public CommandRegistry registry() { return registry; }
}
