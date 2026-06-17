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
    private String systemPrompt = "你是 EasyCode，一个终端 AI 编程助手。用工具操作文件系统，每次一个工具，拿到结果再回答。路径统一用正斜杠 /。用户要求模糊时反问确认。工具结果截断时用 offset 继续读。";

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

    public String systemPromptSafe() {
        if (systemPrompt == null || systemPrompt.isBlank())
            return "你是一个终端 AI 编程助手。用工具操作文件系统。";
        return systemPrompt;
    }
}
