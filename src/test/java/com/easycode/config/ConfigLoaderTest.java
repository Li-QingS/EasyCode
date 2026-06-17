package com.easycode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidConfig() throws Exception {
        String yaml = """
                protocol: anthropic
                model: claude-sonnet-4-20250514
                base_url: https://api.anthropic.com
                api_key: sk-test123
                """;
        Path configFile = tempDir.resolve("easycode.yaml");
        Files.writeString(configFile, yaml);

        Config config = ConfigLoader.load(configFile.toString());

        assertEquals("anthropic", config.protocol());
        assertEquals("claude-sonnet-4-20250514", config.model());
        assertEquals("https://api.anthropic.com", config.baseUrl());
        assertEquals("sk-test123", config.apiKey());
    }

    @Test
    void shouldThrowWhenFileMissing() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load("/nonexistent/easycode.yaml"));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void shouldThrowWhenProtocolMissing() throws Exception {
        String yaml = """
                model: claude-sonnet-4-20250514
                base_url: https://api.anthropic.com
                api_key: sk-test123
                """;
        Path configFile = tempDir.resolve("easycode.yaml");
        Files.writeString(configFile, yaml);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(configFile.toString()));
        assertTrue(ex.getMessage().contains("protocol"));
    }

    @Test
    void shouldThrowWhenProtocolInvalid() throws Exception {
        String yaml = """
                protocol: unknown
                model: claude-sonnet-4-20250514
                base_url: https://api.anthropic.com
                api_key: sk-test123
                """;
        Path configFile = tempDir.resolve("easycode.yaml");
        Files.writeString(configFile, yaml);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(configFile.toString()));
        assertTrue(ex.getMessage().contains("不支持的协议"));
    }

    @Test
    void shouldThrowWhenApiKeyMissing() throws Exception {
        String yaml = """
                protocol: openai
                model: gpt-4o
                base_url: https://api.openai.com
                """;
        Path configFile = tempDir.resolve("easycode.yaml");
        Files.writeString(configFile, yaml);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(configFile.toString()));
        assertTrue(ex.getMessage().contains("api_key"));
    }
}
