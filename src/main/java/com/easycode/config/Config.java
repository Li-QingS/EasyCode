package com.easycode.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/** LLM 配置 */
public final class Config {
    private String protocol;
    private String model;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("api_key")
    private String apiKey;

    private int contextWindow = 128_000;
    private int toolTimeout = 30;

    @JsonProperty("system_prompt")
    private String systemPrompt = """
        你是 EasyCode，一个终端 AI 编程助手。你可以直接操作文件系统，调用工具执行实际任务。

        你有以下工具可用：
        - read_file: 读取项目中的文件内容。参数 path（文件路径）。
        - write_file: 创建新文件或覆盖已有文件。参数 path（路径）、content（写入内容）。
        - edit_file: 精确替换文件中的一段文本（必须唯一匹配）。参数 path（路径）、old（要替换的原文）、new（新文本）。
        - exec_command: 在项目根目录执行 shell 命令。参数 command（命令字符串）。常用：ls 查看文件、tree 看目录树、mvn 构建项目。
        - find_files: 按 glob 模式查找文件。参数 pattern（如 "*" 看所有文件、"*.java" 找 Java 文件）、dir（可选，搜索目录）。
        - grep_code: 用正则表达式搜索代码内容。当用户说"找一下XX的代码""搜索XX""XX在哪里"时优先使用。参数 pattern（正则）、dir（可选）、fileFilter（glob过滤文件名）、contextLines（上下文行数）。

        重要提示：
        - 所有文件路径使用正斜杠 /，不要用反斜杠 \\
        - 优先使用相对于项目根目录的相对路径（如 src/main/java/com/easycode/Main.java）
        - 如果用户给出了绝对路径，确保使用正斜杠格式

        4. 如果用户要求不明确（如"改为合适的内容"），不要猜测，直接反问用户具体要改成什么或给出建议让用户确认。

        重要规则：
        1. 当用户要求查看项目结构时用 exec_command ls 或 find_files。当用户要"找XX的代码""搜索XX""XX在哪里"时用 grep_code，不要用 exec_command ls 代替。
        2. 工具结果末尾如果出现"用 offset=N 继续"，必须立即再调该工具传 offset=N 继续读，拿到完整内容再回答。
        3. 工具执行失败时，向用户说明失败原因，不要反复重试。
        """;

    public Config() {}

    public Config(String protocol, String model, String baseUrl, String apiKey) {
        this.protocol = protocol; this.model = model; this.baseUrl = baseUrl; this.apiKey = apiKey;
    }

    public Config(String protocol, String model, String baseUrl, String apiKey,
                  int contextWindow, int toolTimeout, String systemPrompt) {
        this(protocol, model, baseUrl, apiKey);
        this.contextWindow = contextWindow;
        this.toolTimeout = toolTimeout;
        this.systemPrompt = systemPrompt;
    }

    public String protocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String model() { return model; }
    public void setModel(String model) { this.model = model; }
    public String baseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String apiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public int contextWindow() { return contextWindow; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }
    public int toolTimeout() { return toolTimeout; }
    public void setToolTimeout(int toolTimeout) { this.toolTimeout = toolTimeout; }
    public String systemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    /** 安全获取：如果反序列化后为 null，返回默认值 */
    public String systemPromptSafe() {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return "你是一个终端 AI 编程助手。使用工具来读取文件、搜索代码、执行命令。";
        }
        return systemPrompt;
    }
}
