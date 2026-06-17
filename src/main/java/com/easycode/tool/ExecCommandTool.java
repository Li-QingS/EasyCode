package com.easycode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.util.Map;
import java.util.Set;

public final class ExecCommandTool implements Tool {
    private static final ObjectMapper json = new ObjectMapper();
    private static final int TIMEOUT_SEC = 30;
    // 危险命令黑名单
    private static final String DANGER = "rm\\s+-rf\\s*/|mkfs|:()\\{";

    // 命令语义表：某些命令退出码 1 不算错误
    // key = 命令名，value = 最小错误退出码（>= 此值才算 isError）
    private static final Map<String, Integer> EXIT_SEMANTICS = Map.of(
        "grep", 2,      // 1=没找到匹配，不是错误
        "diff", 2,      // 1=文件有差异，不是错误
        "find", 2,      // 1=部分目录不可访问，不是错误
        "cmp", 2,       // 1=文件不同，不是错误
        "rg", 2         // ripgrep: 1=没找到匹配
    );

    /** 从命令字符串中提取基础命令名 */
    private static String baseCmd(String fullCmd) {
        String s = fullCmd.trim();
        // 去掉前导的非字母字符（如 ./ 路径前缀）
        int start = 0;
        while (start < s.length() && !Character.isLetter(s.charAt(start))) start++;
        int end = start;
        while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_' || s.charAt(end) == '-'))
            end++;
        return s.substring(start, end);
    }

    @Override public String name() { return "exec_command"; }
    @Override public String description() {
        return "在项目根目录执行 shell 命令并返回标准输出、标准错误和退出码。\n" +
            "参数 command(必填): 通过 bash -c 执行的命令字符串。\n" +
            "常用场景: mvn compile 编译、mvn test 运行测试、ls 列出目录内容、tree 看目录树、java 运行代码。\n" +
            "退出码语义: grep/diff/find 的退出码1不算错误(无匹配/有差异), 其他命令非零为错误。超时直接报error。\n" +
            "列出目录内容用此工具。搜索特定文件用 find_files, 搜索代码内容用 grep_code, 读取文件用 read_file。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = json.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("command").put("type", "string").put("description", "要执行的命令");
        s.putArray("required").add("command");
        return s;
    }

    @Override
    public ToolResult execute(JsonNode input) {
        long start = System.currentTimeMillis();
        try {
            String cmd = input.get("command").asText();
            if (cmd.matches(".*(" + DANGER + ").*")) {
                return ToolResult.err(name(), "危险命令被拦截: " + cmd, System.currentTimeMillis() - start);
            }
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            Process proc = pb.start();
            // 分别读 stdout 和 stderr
            Future<String> outFuture = Executors.newSingleThreadExecutor().submit(() -> {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                return sb.toString();
            });
            Future<String> errFuture = Executors.newSingleThreadExecutor().submit(() -> {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                return sb.toString();
            });
            String stdout = outFuture.get(TIMEOUT_SEC, TimeUnit.SECONDS);
            String stderr = errFuture.get(1, TimeUnit.SECONDS);
            int exitCode = proc.waitFor();
            String baseName = baseCmd(cmd);
            int errorThreshold = EXIT_SEMANTICS.getOrDefault(baseName, 1);
            boolean isError = exitCode >= errorThreshold;

            StringBuilder result = new StringBuilder();
            if (!stdout.isBlank()) {
            if (stdout.length() > 1000) stdout = stdout.substring(0, 1000) + "\n... (截断，输出过长)";
                result.append(stdout.trim());
            }
            if (!stderr.isBlank()) {
                if (stderr.length() > 500) stderr = stderr.substring(0, 500) + "\n... (截断)";
                result.append("\n[stderr]\n").append(stderr.trim());
            }
            if (exitCode != 0) result.append("\n[退出码: ").append(exitCode).append("]");
            if (result.isEmpty()) result.append("(无输出)");

            return isError
                ? ToolResult.err(name(), result.toString(), System.currentTimeMillis() - start)
                : ToolResult.ok(name(), result.toString(), System.currentTimeMillis() - start);
        } catch (java.util.concurrent.TimeoutException e) {
            return ToolResult.err(name(), "命令超时（>" + TIMEOUT_SEC + "s）", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.err(name(), "执行失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    // 元信息
    @Override public Permission permission() { return Permission.READ_WRITE; }
    @Override public Category category() { return Category.SHELL; }
    @Override public boolean isDestructive() { return true; }
    @Override public boolean requiresApproval() { return true; }
    @Override public State defaultState() { return State.APPROVAL_REQUIRED; }
}
