package com.easycode.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadEmptyWhenNoFiles() {
        Map<String, McpServerConfig> result = McpConfigLoader.load(tempDir);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldLoadUserConfig() throws Exception {
        Map<String, McpServerConfig> servers = new java.util.LinkedHashMap<>();
        Path configDir = tempDir.resolve(".easycode");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mcp.yaml"), """
                mcp_servers:
                  demo:
                    type: stdio
                    command: echo
                    args: ["hello"]
                """);
        McpConfigLoader.loadFile(configDir.resolve("mcp.yaml"), servers);
        assertEquals(1, servers.size());
        assertTrue(servers.containsKey("demo"));
        assertEquals("stdio", servers.get("demo").type());
        assertEquals("echo", servers.get("demo").command());
    }

    @Test
    void shouldValidateServerType() {
        Map<String, McpServerConfig> servers = new java.util.LinkedHashMap<>();
        McpConfigLoader.loadFile(writeYaml("""
                mcp_servers:
                  bad:
                    type: invalid
                    command: echo
                """), servers);
        assertFalse(servers.containsKey("bad"));
    }

    @Test
    void shouldRequireCommandForStdio() {
        Map<String, McpServerConfig> servers = new java.util.LinkedHashMap<>();
        McpConfigLoader.loadFile(writeYaml("""
                mcp_servers:
                  missing:
                    type: stdio
                    args: ["hello"]
                """), servers);
        assertFalse(servers.containsKey("missing"));
    }

    @Test
    void shouldRequireUrlForHttp() {
        Map<String, McpServerConfig> servers = new java.util.LinkedHashMap<>();
        McpConfigLoader.loadFile(writeYaml("""
                mcp_servers:
                  missing:
                    type: http
                    headers:
                      X-Test: value
                """), servers);
        assertFalse(servers.containsKey("missing"));
    }

    @Test
    void shouldLoadHttpServer() {
        Map<String, McpServerConfig> servers = new java.util.LinkedHashMap<>();
        McpConfigLoader.loadFile(writeYaml("mcp_servers:\n"
                + "  api:\n"
                + "    type: http\n"
                + "    url: https://example.com/mcp\n"
                + "    headers:\n"
                + "      Authorization: Bearer mytoken\n"), servers);
        assertTrue(servers.containsKey("api"));
        assertEquals("http", servers.get("api").type());
        assertEquals("https://example.com/mcp", servers.get("api").url());
    }

    @Test
    void shouldExpandEnvVars() {
        // Set an env var and verify expansion
        Map<String, McpServerConfig> servers = new java.util.LinkedHashMap<>();
        McpConfigLoader.loadFile(writeYaml("mcp_servers:\n"
                + "  demo:\n"
                + "    type: http\n"
                + "    url: https://example.com\n"
                + "    headers:\n"
                + "      Authorization: Bearer ${PATH}\n"), servers);
        McpServerConfig cfg = servers.get("demo");
        assertNotNull(cfg);
        // PATH always exists, so expansion should replace ${PATH}
        String path = System.getenv("PATH");
        String expected = "Bearer " + (path != null ? path : "");
        assertEquals(expected, cfg.headers().get("Authorization"));
    }

    @Test
    void shouldWarnOnUndefinedVar() {
        Map<String, McpServerConfig> servers = new java.util.LinkedHashMap<>();
        McpConfigLoader.loadFile(writeYaml("mcp_servers:\n"
                + "  demo:\n"
                + "    type: http\n"
                + "    url: https://example.com\n"
                + "    headers:\n"
                + "      X-Test: ${NONEXISTENT_VAR_12345}\n"), servers);
        McpServerConfig cfg = servers.get("demo");
        assertNotNull(cfg);
        // Undefined var expands to empty string
        assertEquals("", cfg.headers().get("X-Test"));
    }

    @Test
    void shouldMergeProjectOverUser() {
        Map<String, McpServerConfig> user = Map.of(
            "shared", new McpServerConfig("stdio", "user-cmd", java.util.List.of(), Map.of(), null, Map.of()),
            "user-only", new McpServerConfig("stdio", "user-cmd2", java.util.List.of(), Map.of(), null, Map.of())
        );
        Map<String, McpServerConfig> project = Map.of(
            "shared", new McpServerConfig("http", null, java.util.List.of(), Map.of(), "https://project.example.com", Map.of())
        );
        Map<String, McpServerConfig> merged = McpConfigLoader.merge(user, project);
        assertEquals(2, merged.size());
        assertEquals("http", merged.get("shared").type()); // project wins
        assertEquals("https://project.example.com", merged.get("shared").url());
        assertTrue(merged.containsKey("user-only"));
    }

    @Test
    void shouldHandleInvalidYamlGracefully() {
        Map<String, McpServerConfig> servers = new java.util.LinkedHashMap<>();
        McpConfigLoader.loadFile(writeYaml("invalid: [not valid yaml structure"), servers);
        assertTrue(servers.isEmpty());
    }

    @Test
    void shouldHandleMissingType() {
        Map<String, McpServerConfig> servers = new java.util.LinkedHashMap<>();
        McpConfigLoader.loadFile(writeYaml("""
                mcp_servers:
                  notype:
                    command: echo
                """), servers);
        assertFalse(servers.containsKey("notype"));
    }

    private Path writeYaml(String content) {
        try {
            Path f = tempDir.resolve("test-mcp-" + System.nanoTime() + ".yaml");
            Files.writeString(f, content);
            return f;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
