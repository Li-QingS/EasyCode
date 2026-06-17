package com.easycode.provider;

import com.easycode.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFactoryTest {

    @Test
    void shouldCreateAnthropicProvider() {
        Config config = new Config("anthropic", "claude-sonnet-4-20250514",
                "https://api.anthropic.com", "sk-test");
        LlmProvider provider = ProviderFactory.create(config);
        assertInstanceOf(AnthropicProvider.class, provider);
    }

    @Test
    void shouldCreateOpenAIProvider() {
        Config config = new Config("openai", "gpt-4o",
                "https://api.openai.com", "sk-test");
        LlmProvider provider = ProviderFactory.create(config);
        assertInstanceOf(OpenAIProvider.class, provider);
    }

    @Test
    void shouldThrowForUnknownProtocol() {
        Config config = new Config("unknown", "some-model",
                "https://example.com", "sk-test");
        assertThrows(IllegalArgumentException.class,
                () -> ProviderFactory.create(config));
    }
}
