package com.easycode.command;

import java.util.ArrayList;
import java.util.List;

/**
 * 注册全部内置命令到 CommandRegistry。
 * 当前共 10 个：/help /compact /clear /plan /do /session /memory /permission /status /review
 */
public final class BuiltinCommands {
    private BuiltinCommands() {}

    public static List<CommandDef> all(UiController ui, CommandRegistry registry) {
        List<CommandDef> list = new ArrayList<>();

        // /help — 显示帮助信息
        list.add(CommandDef.builder("help", args -> {
            if (args != null && !args.isEmpty()) {
                CommandDef target = registry.lookup(args.trim());
                if (target != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("/").append(target.name());
                    if (!target.paramHint().isEmpty()) sb.append(' ').append(target.paramHint());
                    if (!target.aliases().isEmpty())
                        sb.append("  (别名: ").append(String.join(", ", target.aliases())).append(')');
                    sb.append("\n").append(target.description());
                    return new CommandResult.Message(sb.toString());
                }
                return new CommandResult.NotFound(args.trim());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("可用命令:\n");
            for (CommandDef cmd : registry.visible()) {
                String aliases = cmd.aliases().isEmpty() ? "" :
                    " (" + String.join(", ", cmd.aliases()) + ")";
                sb.append("  /").append(cmd.name());
                if (!cmd.paramHint().isEmpty()) sb.append(' ').append(cmd.paramHint());
                sb.append(aliases).append("\n");
                sb.append("    ").append(cmd.description()).append("\n");
            }
            sb.append("\n模式切换: /permission <default|edit|plan|bypass>\n");
            sb.append("输入 /help <命令> 查看命令详情\n");
            return new CommandResult.Message(sb.toString());
        })
            .aliases("h", "?")
            .description("显示所有命令或指定命令的详细帮助")
            .usage("/help [command]")
            .paramHint("[command]")
            .type(CommandType.LOCAL)
            .build());

        // /compact — 手动压缩上下文
        list.add(CommandDef.builder("compact", args -> {
            ui.triggerCompact();
            return new CommandResult.Ok();
        })
            .aliases("cmp")
            .description("手动触发上下文压缩（摘要+清理旧消息）")
            .usage("/compact")
            .type(CommandType.LOCAL)
            .build());

        // /clear — 清屏
        list.add(CommandDef.builder("clear", args -> {
            ui.clearScreen();
            return new CommandResult.Ok();
        })
            .aliases("cls")
            .description("清空终端屏幕")
            .usage("/clear")
            .type(CommandType.UI)
            .build());

        // /plan — 切换到计划模式
        list.add(CommandDef.builder("plan", args -> {
            ui.switchMode(com.easycode.permission.PermissionMode.PLAN);
            return new CommandResult.Message("已切换到 PLAN（计划模式）— 仅开放只读工具");
        })
            .description("切换到计划模式：仅开放只读工具，其他操作需确认")
            .usage("/plan")
            .type(CommandType.UI)
            .build());

        // /do — 切换回执行模式 (DEFAULT)
        list.add(CommandDef.builder("do", args -> {
            ui.switchMode(com.easycode.permission.PermissionMode.DEFAULT);
            return new CommandResult.Message("已切换到 DO（执行模式）");
        })
            .aliases("exec")
            .description("切换回执行模式，恢复完整工具访问")
            .usage("/do")
            .type(CommandType.UI)
            .build());

        // /session — 会话管理 + 恢复历史会话
        list.add(CommandDef.builder("session", args -> {
            if (args != null && !args.trim().isEmpty()) {
                // /session resume <id> — 直接恢复指定会话
                String[] parts = args.trim().split("\s+", 2);
                if (parts.length >= 2 && "resume".equalsIgnoreCase(parts[0])) {
                    try {
                        java.nio.file.Path jsonl = com.easycode.session.SessionContext.jsonlPath(parts[1]);
                        var msgs = com.easycode.session.SessionResumer.resume(jsonl);
                        return new CommandResult.Message("已恢复会话 " + parts[1] + "，共 " + msgs.size() + " 条消息");
                    } catch (Exception e) {
                        return new CommandResult.Error("恢复失败: " + e.getMessage());
                    }
                }
                return new CommandResult.Error("用法: /session resume <id>");
            }
            // 无参数：展示当前会话 + 可恢复历史列表
            String sid = ui.sessionId();
            int[] tokens = ui.tokenUsage();
            StringBuilder sb = new StringBuilder();
            sb.append("当前会话: ").append(sid).append("\n");
            sb.append("上下文 Tokens: ").append(tokens[4]).append(" / ").append(tokens[5]).append("\n");
            sb.append("累计输入: ").append(tokens[2]).append("  累计输出: ").append(tokens[3]).append("\n");
            var sessions = com.easycode.session.SessionResumer.scanAll(java.nio.file.Path.of(".easycode/sessions"));
            if (!sessions.isEmpty()) {
                sb.append("\n可恢复的会话:\n");
                for (int i = 0; i < sessions.size(); i++) {
                    var s = sessions.get(i);
                    String title = s.title() != null ? s.title() : "(无标题)";
                    sb.append("  [").append(i + 1).append("] ").append(title)
                      .append("  —  ").append(com.easycode.session.SessionResumer.relativeTime(s.lastModified())).append("\n");
                }
                sb.append("输入数字恢复 (1-").append(sessions.size()).append(", Enter=取消): ");
                ui.showMessage(sb.toString());
                try {
                    StringBuilder input = new StringBuilder();
                    while (true) {
                        int ch = java.lang.System.in.read();
                        if (ch == '\n' || ch == '\r') break;
                        if (ch >= '0' && ch <= '9') input.append((char) ch);
                    }
                    if (input.length() > 0) {
                        int idx = Integer.parseInt(input.toString()) - 1;
                        if (idx >= 0 && idx < sessions.size()) {
                            var sel = sessions.get(idx);
                            java.nio.file.Path jsonl = com.easycode.session.SessionContext.jsonlPath(sel.id());
                            var msgs = com.easycode.session.SessionResumer.resume(jsonl);
                            return new CommandResult.Message("已恢复会话 " + sel.id() + "，共 " + msgs.size() + " 条消息");
                        }
                        return new CommandResult.Error("无效序号");
                    }
                } catch (java.io.IOException e) { /* 取消 */ }
                return new CommandResult.Ok();
            }
            return new CommandResult.Message(sb.toString());
        })
            .aliases("sess")
            .description("显示当前会话信息，列出可恢复的历史会话")
            .usage("/session [resume <id>]")
            .paramHint("[resume <id>]")
            .type(CommandType.LOCAL)
            .build());

        // /memory — 记忆信息
        list.add(CommandDef.builder("memory", args -> {
            String msg = "记忆存储位置:\n" +
                "  项目记忆: .easycode/memory/\n" +
                "  用户记忆: ~/.easycode/memory/\n" +
                "记忆由系统每 5 轮或检测到关键词时自动更新。";
            return new CommandResult.Message(msg);
        })
            .aliases("mem")
            .description("显示记忆系统信息")
            .usage("/memory")
            .type(CommandType.LOCAL)
            .build());

        // /permission — 权限模式管理
        list.add(CommandDef.builder("permission", args -> {
            var current = ui.currentMode();
            if (args != null && !args.isEmpty()) {
                var mode = parseMode(args.trim());
                if (mode != null) {
                    ui.switchMode(mode);
                    return new CommandResult.Message("已切换到 " + mode.name());
                }
                return new CommandResult.Error("无效模式: " + args + "。可用: default / edit / plan / bypass");
            }
            // 无参数：显示数字菜单并等待输入
            System.out.println("当前模式: " + current.name());
            System.out.println("[1] DEFAULT        只读直接执行，写/命令需确认");
            System.out.println("[2] ACCEPT_EDITS   只读和写直接执行，命令需确认");
            System.out.println("[3] PLAN           仅开放只读工具（计划模式）");
            System.out.println("[4] BYPASS         全部直接执行（黑名单除外）");
            System.out.print("输入数字 (1-4, Enter=取消): ");
            System.out.flush();
            try {
                int ch = java.lang.System.in.read();
                while (java.lang.System.in.available() > 0) java.lang.System.in.read();
                var mode = switch (ch) {
                    case '1' -> com.easycode.permission.PermissionMode.DEFAULT;
                    case '2' -> com.easycode.permission.PermissionMode.ACCEPT_EDITS;
                    case '3' -> com.easycode.permission.PermissionMode.PLAN;
                    case '4' -> com.easycode.permission.PermissionMode.BYPASS_PERMISSIONS;
                    default -> null;
                };
                if (mode != null) {
                    ui.switchMode(mode);
                    return new CommandResult.Message("已切换到 " + mode.name());
                }
            } catch (java.io.IOException e) { /* 忽略 */ }
            return new CommandResult.Ok();
        })
            .aliases("perm", "mode")
            .description("查看或切换权限模式")
            .usage("/permission [default|edit|plan|bypass]")
            .paramHint("[mode]")
            .type(CommandType.UI)
            .build());

        // /status — 系统状态
        list.add(CommandDef.builder("status", args -> {
            int[] t = ui.tokenUsage();
            var mode = ui.currentMode();
            long uptime = System.currentTimeMillis() - ui.startTimeMs();
            long sec = uptime / 1000;
            String up = String.format("%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
            StringBuilder sb = new StringBuilder();
            sb.append("会话: ").append(ui.sessionId()).append("\n");
            sb.append("模式: ").append(mode.name()).append("\n");
            sb.append("运行时间: ").append(up).append("\n");
            sb.append("Tokens — 本轮: 入").append(t[0]).append(" 出").append(t[1]);
            sb.append("  累计: 入").append(t[2]).append(" 出").append(t[3]).append("\n");
            sb.append("上下文窗口: ").append(t[4]).append(" / ").append(t[5]);
            return new CommandResult.Message(sb.toString());
        })
            .aliases("stat", "st")
            .description("显示当前系统状态（模式、Token、运行时间）")
            .usage("/status")
            .type(CommandType.LOCAL)
            .build());

        // /review — 代码审查预设提示词
        list.add(CommandDef.builder("review", args -> {
            String prompt = "请审查当前项目代码，找出潜在问题（bug、安全隐患、性能问题、代码异味、设计缺陷），" +
                "并给出改进建议。请按严重程度排序，每个问题说明位置、原因和修复方案。";
            return new CommandResult.Prompt(prompt);
        })
            .aliases("rv")
            .description("注入代码审查提示词，让 AI 审查当前项目代码")
            .usage("/review")
            .type(CommandType.PROMPT)
            .build());

        return list;
    }

    private static com.easycode.permission.PermissionMode parseMode(String s) {
        return switch (s.toLowerCase()) {
            case "default", "def" -> com.easycode.permission.PermissionMode.DEFAULT;
            case "edit", "accept_edits" -> com.easycode.permission.PermissionMode.ACCEPT_EDITS;
            case "plan" -> com.easycode.permission.PermissionMode.PLAN;
            case "bypass", "bypass_permissions" -> com.easycode.permission.PermissionMode.BYPASS_PERMISSIONS;
            default -> null;
        };
    }
}
