package com.easycode.command;

import java.util.List;
import java.util.function.Function;

public final class CommandDef {
    private final String name;
    private final List<String> aliases;
    private final String description;
    private final String usage;
    private final CommandType type;
    private final String paramHint;
    private final boolean hidden;
    private final Function<String, CommandResult> handler;

    private CommandDef(Builder builder) {
        this.name = builder.name;
        this.aliases = List.copyOf(builder.aliases);
        this.description = builder.description;
        this.usage = builder.usage;
        this.type = builder.type;
        this.paramHint = builder.paramHint;
        this.hidden = builder.hidden;
        this.handler = builder.handler;
    }

    public String name() { return name; }
    public List<String> aliases() { return aliases; }
    public String description() { return description; }
    public String usage() { return usage; }
    public CommandType type() { return type; }
    public String paramHint() { return paramHint; }
    public boolean hidden() { return hidden; }
    public Function<String, CommandResult> handler() { return handler; }

    public static Builder builder(String name, Function<String, CommandResult> handler) {
        return new Builder(name, handler);
    }

    public static final class Builder {
        private final String name;
        private final Function<String, CommandResult> handler;
        private List<String> aliases = List.of();
        private String description = "";
        private String usage = "";
        private CommandType type = CommandType.LOCAL;
        private String paramHint = "";
        private boolean hidden = false;

        private Builder(String name, Function<String, CommandResult> handler) {
            this.name = name;
            this.handler = handler;
        }

        public Builder aliases(String... aliases) { this.aliases = List.of(aliases); return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder usage(String usage) { this.usage = usage; return this; }
        public Builder type(CommandType type) { this.type = type; return this; }
        public Builder paramHint(String paramHint) { this.paramHint = paramHint; return this; }
        public Builder hidden(boolean hidden) { this.hidden = hidden; return this; }
        public CommandDef build() { return new CommandDef(this); }
    }
}
