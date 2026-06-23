package com.easycode.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AgentDefLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadFromSingleDir() throws Exception {
        Files.createDirectories(tempDir.resolve(".easycode/agents"));
        Files.writeString(tempDir.resolve(".easycode/agents/tester.md"), """
            ---
            name: tester
            description: test agent
            tools_allow:
              - read_file
            max_turns: 5
            ---
            # Tester
            You are a test agent.
            """);

        Map<String, AgentDef> defs = AgentDefLoader.loadAll(tempDir);
        assertTrue(defs.containsKey("tester"));
        AgentDef d = defs.get("tester");
        assertEquals("test agent", d.description());
        assertEquals(List.of("read_file"), d.toolsAllow());
        assertEquals(5, d.maxTurns());
        assertTrue(d.systemPrompt().contains("You are a test agent"));
    }

    @Test
    void projectOverridesUser() throws Exception {
        // User definition
        Path userDir = Path.of(System.getProperty("user.home"), ".easycode/agents");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("dup.md"), """
            ---
            name: dup
            description: user version
            ---
            # User
            """);

        // Project definition (should win)
        Files.createDirectories(tempDir.resolve(".easycode/agents"));
        Files.writeString(tempDir.resolve(".easycode/agents/dup.md"), """
            ---
            name: dup
            description: project version
            ---
            # Project
            """);

        Map<String, AgentDef> defs = AgentDefLoader.loadAll(tempDir);
        assertEquals("project version", defs.get("dup").description(),
            "Project definition should override user definition");

        // Cleanup
        Files.deleteIfExists(userDir.resolve("dup.md"));
    }

    @Test
    void shouldUseDefaultValues() throws Exception {
        Files.createDirectories(tempDir.resolve(".easycode/agents"));
        Files.writeString(tempDir.resolve(".easycode/agents/minimal.md"), """
            ---
            name: minimal
            ---
            # Minimal
            """);

        Map<String, AgentDef> defs = AgentDefLoader.loadAll(tempDir);
        AgentDef d = defs.get("minimal");
        assertEquals("minimal", d.name());
        assertEquals(10, d.maxTurns()); // default
        assertTrue(d.toolsAllow().isEmpty());
        assertTrue(d.toolsDeny().isEmpty());
        assertEquals("", d.model());
        assertEquals("", d.permission());
    }

    @Test
    void builtinReviewerExists() {
        // Load from classpath builtin
        Map<String, AgentDef> defs = AgentDefLoader.loadAll(tempDir);
        assertTrue(defs.containsKey("reviewer"), "Builtin reviewer should exist");
        AgentDef r = defs.get("reviewer");
        assertTrue(r.toolsAllow().contains("read_file"));
        assertTrue(r.toolsAllow().contains("grep_code"));
        System.out.println("[test] builtin reviewer: " + r.description());
    }
}
