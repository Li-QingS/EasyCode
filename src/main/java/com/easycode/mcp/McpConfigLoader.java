package com.easycode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class McpConfigLoader {
    private static final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private McpConfigLoader() {}

    public static Map<String, McpServerConfig> load(Path projectRoot) {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        // 用户级
        String home = System.getProperty("user.home");
        if (home != null) {
            loadFile(Path.of(home, ".easycode", "mcp.yaml"), servers);
        }
        // 项目级（easycode.yaml 由 ConfigLoader 加载）
        // 这里只做补充：如果项目级有 easycode.yaml 且含 mcp_servers，在 Config.java 中已经有该字段
        return servers;
    }

    static void loadFile(Path path, Map<String, McpServerConfig> target) {
        File f = path.toFile();
        if (!f.exists()) return;
        try {
            Map<String, Object> raw = yaml.readValue(f, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> mcpServers = (Map<String, Object>) raw.get("mcp_servers");
            if (mcpServers == null) return;
            for (var entry : mcpServers.entrySet()) {
                String name = entry.getKey();
                @SuppressWarnings("unchecked")
                Map<String, Object> def = (Map<String, Object>) entry.getValue();
                if (def == null) continue;
                String type = (String) def.get("type");
                if (!"stdio".equals(type) && !"http".equals(type)) {
                    System.err.println("[mcp] warn: skip server " + name + ": invalid type " + type);
                    continue;
                }
                String command = (String) def.get("command");
                String url = (String) def.get("url");
                if ("stdio".equals(type) && (command == null || command.isEmpty())) {
                    System.err.println("[mcp] warn: skip server " + name + ": missing command");
                    continue;
                }
                if ("http".equals(type) && (url == null || url.isEmpty())) {
                    System.err.println("[mcp] warn: skip server " + name + ": missing url");
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<String> args = def.containsKey("args") ? (List<String>) def.get("args") : List.of();
                @SuppressWarnings("unchecked")
                Map<String, String> env = def.containsKey("env") ? toStringMap((Map<String, Object>) def.get("env")) : Map.of();
                @SuppressWarnings("unchecked")
                Map<String, String> headers = def.containsKey("headers") ? toStringMap((Map<String, Object>) def.get("headers")) : Map.of();
                // expand vars
                env = expandMap(env, name);
                headers = expandMap(headers, name);
                target.put(name, new McpServerConfig(type, command, args, env, url, headers));
            }
        } catch (IOException e) {
            System.err.println("[mcp] warn: failed to parse " + path + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> toStringMap(Map<String, Object> map) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var e : map.entrySet()) result.put(e.getKey(), String.valueOf(e.getValue()));
        return result;
    }

    private static Map<String, String> expandMap(Map<String, String> map, String serverName) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            String expanded = expandVars(e.getValue(), serverName);
            result.put(e.getKey(), expanded);
        }
        return result;
    }

    private static String expandVars(String value, String serverName) {
        Matcher m = VAR_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String envVal = System.getenv(varName);
            if (envVal == null) {
                System.err.println("[mcp] warn: undefined env var ${" + varName + "} referenced by server " + serverName);
                m.appendReplacement(sb, "");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(envVal));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 合并项目级 mcpServers（从 Config.java 的 mcpServers 字段） */
    public static Map<String, McpServerConfig> merge(
            Map<String, McpServerConfig> user, Map<String, McpServerConfig> project) {
        LinkedHashMap<String, McpServerConfig> result = new LinkedHashMap<>(user);
        result.putAll(project);  // 项目级完整覆盖
        return result;
    }
}
