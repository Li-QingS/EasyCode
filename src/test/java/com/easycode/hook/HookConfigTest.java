package com.easycode.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HookConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidConfig() throws Exception {
        String yaml = """
            hooks:
              - name: test-rule
                event: startup
                action:
                  type: shell
                  command: echo hello
            """;
        Files.writeString(tempDir.resolve("easycode.hooks.yaml"), yaml);
        List<HookRule> rules = HookConfig.load(tempDir);
        assertEquals(1, rules.size());
        assertEquals("test-rule", rules.get(0).name());
        assertEquals(HookEvent.STARTUP, rules.get(0).event());
    }

    @Test
    void shouldReturnEmptyWhenNoConfig() {
        List<HookRule> rules = HookConfig.load(tempDir);
        assertTrue(rules.isEmpty());
    }

    @Test
    void shouldRejectMissingEvent() throws Exception {
        String yaml = """
            hooks:
              - name: bad-rule
                action:
                  type: prompt
                  text: hello
            """;
        Files.writeString(tempDir.resolve("easycode.hooks.yaml"), yaml);
        assertThrows(IllegalStateException.class, () -> HookConfig.load(tempDir));
    }

    @Test
    void shouldRejectPreToolWithAsync() throws Exception {
        String yaml = """
            hooks:
              - name: bad-rule
                event: pre-tool
                async: true
                action:
                  type: prompt
                  text: hello
            """;
        Files.writeString(tempDir.resolve("easycode.hooks.yaml"), yaml);
        assertThrows(IllegalStateException.class, () -> HookConfig.load(tempDir));
    }

    @Test
    void shouldRejectShellWithoutCommand() throws Exception {
        String yaml = """
            hooks:
              - name: bad-rule
                event: startup
                action:
                  type: shell
            """;
        Files.writeString(tempDir.resolve("easycode.hooks.yaml"), yaml);
        assertThrows(IllegalStateException.class, () -> HookConfig.load(tempDir));
    }

    @Test
    void shouldRejectHttpWithoutUrl() throws Exception {
        String yaml = """
            hooks:
              - name: bad-rule
                event: startup
                action:
                  type: http
            """;
        Files.writeString(tempDir.resolve("easycode.hooks.yaml"), yaml);
        assertThrows(IllegalStateException.class, () -> HookConfig.load(tempDir));
    }
}
