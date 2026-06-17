package com.easycode.prompt;

import java.time.LocalDate;

/** 运行环境信息采集与渲染 */
public record Environment(
    String workingDir,
    String platform,
    String date,
    String gitStatus,
    String shell,
    String appVersion,
    String model
) {
    public static Environment collect(String appVersion, String model) {
        String dir = System.getProperty("user.dir", ".");
        String plat = System.getProperty("os.name", "unknown");
        String sh = System.getenv("SHELL");
        if (sh == null || sh.isEmpty()) sh = "unknown";
        String git = collectGitStatus();
        return new Environment(dir, plat, LocalDate.now().toString(), git, sh, appVersion, model);
    }

    private static String collectGitStatus() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            String out = sb.toString().trim();
            return out.isEmpty() ? "(clean)" : out;
        } catch (Exception e) {
            return "";
        }
    }

    /** 渲染为多行文本，空字段对应行省略 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("工作目录: ").append(workingDir).append('\n');
        sb.append("平台: ").append(platform).append('\n');
        sb.append("日期: ").append(date).append('\n');
        if (!gitStatus.isEmpty())
            sb.append("Git: ").append(gitStatus).append('\n');
        sb.append("Shell: ").append(shell).append('\n');
        sb.append("版本: ").append(appVersion).append('\n');
        sb.append("模型: ").append(model);
        return sb.toString();
    }
}
