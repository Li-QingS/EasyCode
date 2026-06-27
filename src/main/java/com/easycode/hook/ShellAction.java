package com.easycode.hook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Shell 命令动作 */
public record ShellAction(
    String command,
    String cwd,
    Map<String, String> env,
    long timeoutSec
) implements HookAction {

    public ShellAction {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command is required");
        if (timeoutSec <= 0) timeoutSec = 30;
    }

    @Override
    public String type() { return "shell"; }

    @Override
    public String execute(HookContext ctx) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (isWindows()) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }
            if (cwd != null && !cwd.isBlank()) pb.directory(new java.io.File(cwd));
            if (env != null) pb.environment().putAll(env);
            // 注入 hook 上下文变量为 shell 环境变量（$name, $success 等）
            if (ctx.vars() != null) {
                for (var e : ctx.vars().entrySet()) {
                    Object v = e.getValue();
                    if (v != null) pb.environment().put(e.getKey(), v.toString());
                }
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "[timeout after " + timeoutSec + "s]";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return truncate(out, 4000);
        } catch (Exception e) {
            return "[shell error: " + e.getMessage() + "]";
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n...(truncated)";
    }
}
