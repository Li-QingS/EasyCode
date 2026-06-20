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

    public CommandDispatcher(CommandRegistry registry, UiController ui) {
        this.registry = registry;
        this.ui = ui;
    }

    /** 注册全部内置命令并锁定注册表 */
    public void setUi(UiController ui) { this.ui = ui; }
    public void registerBuiltins() {
        List<CommandDef> builtins = BuiltinCommands.all(ui, registry);
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
