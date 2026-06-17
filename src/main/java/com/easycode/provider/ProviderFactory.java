package com.easycode.provider;

import com.easycode.config.Config;

/** 根据配置创建对应的 LlmProvider 实例 */
public final class ProviderFactory {

    private ProviderFactory() {}

    public static LlmProvider create(Config config) {
        if ("anthropic".equals(config.protocol())) return new AnthropicProvider(config);
        if ("openai".equals(config.protocol())) return new OpenAIProvider(config);
        throw new IllegalArgumentException("不支持的协议: " + config.protocol());
    }
}
