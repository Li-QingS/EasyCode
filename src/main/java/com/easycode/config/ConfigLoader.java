package com.easycode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;

/** 配置加载与校验 */
public final class ConfigLoader {

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {}

    /** 从指定路径加载并校验配置 */
    public static Config load(String path) {
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalArgumentException("配置文件不存在: " + path);
        }
        Config config;
        try {
            config = mapper.readValue(file, Config.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("配置文件解析失败: " + e.getMessage(), e);
        }
        validate(config);
        return config;
    }

    private static void validate(Config config) {
        if (config.protocol() == null || config.protocol().isBlank()) {
            throw new IllegalArgumentException("配置字段 protocol 不能为空");
        }
        if (!"anthropic".equals(config.protocol()) && !"openai".equals(config.protocol())) {
            throw new IllegalArgumentException("不支持的协议: " + config.protocol() + "（仅支持 anthropic 或 openai）");
        }
        if (config.model() == null || config.model().isBlank()) {
            throw new IllegalArgumentException("配置字段 model 不能为空");
        }
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new IllegalArgumentException("配置字段 base_url 不能为空");
        }
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalArgumentException("配置字段 api_key 不能为空");
        }
    }
}
