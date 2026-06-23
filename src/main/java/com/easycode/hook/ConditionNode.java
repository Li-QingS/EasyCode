package com.easycode.hook;

import java.util.List;
import java.util.regex.Pattern;

/** 条件表达式（复用权限规则匹配语法） */
public sealed interface ConditionNode {

    /** 精确匹配 */
    record Equals(String field, String value) implements ConditionNode {}

    /** 反向匹配 */
    record NotEquals(String field, String value) implements ConditionNode {}

    /** 正则匹配 */
    record Regex(String field, String pattern) implements ConditionNode {}

    /** Glob 匹配 */
    record Glob(String field, String pattern) implements ConditionNode {}

    /** 全部满足（AND） */
    record All(List<ConditionNode> conditions) implements ConditionNode {}

    /** 任一满足（OR） */
    record Any(List<ConditionNode> conditions) implements ConditionNode {}

    /** 从 vars 中取值，不存在返回 null */
    static String fieldValue(String field, java.util.Map<String, Object> vars) {
        Object v = vars.get(field);
        return v != null ? v.toString() : null;
    }

    /** 递归匹配 */
    static boolean matches(ConditionNode node, java.util.Map<String, Object> vars) {
        if (node == null) return true;
        if (node instanceof Equals e) {
            String val = fieldValue(e.field, vars);
            return val != null && val.equals(e.value);
        }
        if (node instanceof NotEquals ne) {
            String val = fieldValue(ne.field, vars);
            return val == null || !val.equals(ne.value);
        }
        if (node instanceof Regex r) {
            String val = fieldValue(r.field, vars);
            return val != null && Pattern.compile(r.pattern).matcher(val).find();
        }
        if (node instanceof Glob g) {
            String val = fieldValue(g.field, vars);
            return val != null && matchGlob(val, g.pattern);
        }
        if (node instanceof All a) {
            return a.conditions.stream().allMatch(c -> matches(c, vars));
        }
        if (node instanceof Any a) {
            return a.conditions.stream().anyMatch(c -> matches(c, vars));
        }
        return false;
    }

    private static boolean matchGlob(String str, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else if (c == '.' || c == '(' || c == ')' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '\\' || c == '+' || c == '^'
                    || c == '$' || c == '|') {
                regex.append('\\');
                regex.append(c);
            } else {
                regex.append(c);
            }
        }
        return str.matches(regex.toString());
    }
}
