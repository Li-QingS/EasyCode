package com.easycode.hook;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 从 easycode.hooks.yaml 加载并校验 Hook 规则 */
public final class HookConfig {

    private HookConfig() {}

    @SuppressWarnings("unchecked")
    public static List<HookRule> load(Path workDir) {
        Path configPath = workDir.resolve("easycode.hooks.yaml");
        if (!Files.isRegularFile(configPath)) return List.of();

        String yamlText;
        try { yamlText = Files.readString(configPath); }
        catch (IOException e) { return List.of(); }

        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(yamlText);
        if (root == null) return List.of();

        List<Map<String, Object>> rawList = (List<Map<String, Object>>) root.get("hooks");
        if (rawList == null || rawList.isEmpty()) return List.of();

        List<HookRule> rules = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            Map<String, Object> raw = rawList.get(i);
            String pos = "rule #" + (i + 1);
            rules.add(parseRule(raw, pos));
        }
        return rules;
    }

    @SuppressWarnings("unchecked")
    private static HookRule parseRule(Map<String, Object> raw, String pos) {
        String name = stringField(raw, "name", pos);
        if (name == null || name.isBlank()) throw error(pos, "name is required");

        // --- event ---
        String eventStr = stringField(raw, "event", pos);
        if (eventStr == null || eventStr.isBlank()) throw error(pos, "event is required");
        HookEvent event;
        try { event = HookEvent.valueOf(eventStr.toUpperCase().replace('-', '_')); }
        catch (IllegalArgumentException e) {
            throw error(pos, "unknown event: " + eventStr + ". Valid: " +
                java.util.Arrays.toString(HookEvent.values()).toLowerCase());
        }

        // --- if ---
        ConditionNode condition = null;
        Object ifObj = raw.get("if");
        if (ifObj instanceof Map) {
            condition = parseCondition((Map<String, Object>) ifObj, pos + ".if");
        }

        // --- action ---
        Object actionObj = raw.get("action");
        if (!(actionObj instanceof Map)) throw error(pos, "action is required");
        Map<String, Object> actionMap = (Map<String, Object>) actionObj;
        String actionType = stringField(actionMap, "type", pos + ".action");
        if (actionType == null) throw error(pos + ".action", "type is required");

        HookAction action = switch (actionType.toLowerCase()) {
            case "shell" -> parseShellAction(actionMap, pos);
            case "prompt" -> parsePromptAction(actionMap, pos);
            case "http" -> parseHttpAction(actionMap, pos);
            case "sub-agent" -> new SubAgentAction();
            default -> throw error(pos + ".action", "unknown type: " + actionType);
        };

        boolean once = booleanField(raw, "once");
        boolean async = booleanField(raw, "async");

        // pre-tool 不允许 async
        if (event == HookEvent.PRE_TOOL && async) {
            throw error(pos, "pre-tool event does not allow async=true");
        }

        return new HookRule(name, event, condition, action, once, async);
    }

    @SuppressWarnings("unchecked")
    private static ConditionNode parseCondition(Map<String, Object> map, String pos) {
        if (map.containsKey("all")) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) map.get("all");
            if (list == null || list.isEmpty()) throw error(pos, "all requires at least 1 condition");
            List<ConditionNode> children = new ArrayList<>();
            for (int i = 0; i < list.size(); i++)
                children.add(parseCondition(list.get(i), pos + ".all[" + i + "]"));
            return new ConditionNode.All(children);
        }
        if (map.containsKey("any")) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) map.get("any");
            if (list == null || list.isEmpty()) throw error(pos, "any requires at least 1 condition");
            List<ConditionNode> children = new ArrayList<>();
            for (int i = 0; i < list.size(); i++)
                children.add(parseCondition(list.get(i), pos + ".any[" + i + "]"));
            return new ConditionNode.Any(children);
        }
        if (map.containsKey("equals")) return parseFieldMatch("equals", map, pos);
        if (map.containsKey("not-equals")) return parseFieldMatch("not-equals", map, pos);
        if (map.containsKey("regex")) return parseFieldMatch("regex", map, pos);
        if (map.containsKey("glob")) return parseFieldMatch("glob", map, pos);
        throw error(pos, "unknown condition type, expected: all/any/equals/not-equals/regex/glob");
    }

    @SuppressWarnings("unchecked")
    private static ConditionNode parseFieldMatch(String type, Map<String, Object> map, String pos) {
        Map<String, Object> inner = (Map<String, Object>) map.get(type);
        if (inner == null) throw error(pos, type + " requires field/value");
        String field = stringField(inner, "field", pos);
        String value = stringField(inner, "value", pos);
        if (field == null || value == null) throw error(pos, type + " requires field and value");
        return switch (type) {
            case "equals" -> new ConditionNode.Equals(field, value);
            case "not-equals" -> new ConditionNode.NotEquals(field, value);
            case "regex" -> new ConditionNode.Regex(field, value);
            case "glob" -> new ConditionNode.Glob(field, value);
            default -> throw error(pos, "unknown: " + type);
        };
    }

    @SuppressWarnings("unchecked")
    private static ShellAction parseShellAction(Map<String, Object> m, String pos) {
        String cmd = stringField(m, "command", pos);
        if (cmd == null || cmd.isBlank()) throw error(pos, "shell requires command");
        String cwd = stringField(m, "cwd", pos);
        Map<String, String> env = null;
        if (m.get("env") instanceof Map) env = (Map<String, String>) m.get("env");
        long timeout = longField(m, "timeout", 30);
        return new ShellAction(cmd, cwd, env, timeout);
    }

    private static PromptAction parsePromptAction(Map<String, Object> m, String pos) {
        String text = stringField(m, "text", pos);
        return new PromptAction(text != null ? text : "");
    }

    @SuppressWarnings("unchecked")
    private static HttpAction parseHttpAction(Map<String, Object> m, String pos) {
        String url = stringField(m, "url", pos);
        if (url == null || url.isBlank()) throw error(pos, "http requires url");
        String method = stringField(m, "method", pos);
        Map<String, String> headers = null;
        if (m.get("headers") instanceof Map) headers = (Map<String, String>) m.get("headers");
        String body = stringField(m, "body", pos);
        long timeout = longField(m, "timeout", 30);
        return new HttpAction(url, method != null ? method : "GET", headers, body, timeout);
    }

    private static String stringField(Map<String, Object> m, String key, String pos) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static boolean booleanField(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return s.equalsIgnoreCase("true");
        return false;
    }

    private static long longField(Map<String, Object> m, String key, long defaultVal) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) try { return Long.parseLong(s); } catch (NumberFormatException e) { return defaultVal; }
        return defaultVal;
    }

    private static IllegalStateException error(String pos, String msg) {
        return new IllegalStateException(pos + ": " + msg);
    }
}
