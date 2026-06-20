package com.easycode.command;

/** 命令执行结果 */
public sealed interface CommandResult {
    record Ok() implements CommandResult {}
    record Message(String text) implements CommandResult {}
    record Prompt(String promptText) implements CommandResult {}
    record NotFound(String commandName) implements CommandResult {}
    record Error(String message) implements CommandResult {}
    record Exit() implements CommandResult {}
}
