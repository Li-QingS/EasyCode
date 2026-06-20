package com.easycode.command;

/**
 * 命令解析器：识别斜杠前缀，提取命令名（小写）和参数。
 *
 * <ul>
 *   <li>输入以 '/' 开头才是命令</li>
 *   <li>第一个空格前是命令名，之后是参数</li>
 *   <li>命令名转小写，大小写不敏感</li>
 *   <li>空命令名（输入 "/" 或 "/ "）返回 invalid</li>
 * </ul>
 */
public final class CommandParser {
    private CommandParser() {}

    public record Parsed(String name, String args) {
        public boolean isValid() { return name != null && !name.isEmpty(); }
    }

    /** 无效解析的单例 */
    public static final Parsed INVALID = new Parsed("", "");

    /**
     * 尝试解析用户输入。如果不是命令返回 Optional.empty()，
     * 如果是命令但命令名为空返回 INVALID，正常解析返回 Parsed。
     */
    public static Parsed parse(String input) {
        if (input == null || input.isEmpty()) return INVALID;
        String trimmed = input.stripLeading();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '/') return INVALID;

        String body = trimmed.substring(1); // 去掉 '/'
        if (body.isEmpty()) return INVALID;

        int spaceIdx = body.indexOf(' ');
        String name, args;
        if (spaceIdx == -1) {
            name = body;
            args = "";
        } else {
            name = body.substring(0, spaceIdx);
            args = body.substring(spaceIdx + 1);
        }

        name = name.toLowerCase();
        if (name.isEmpty()) return INVALID;

        return new Parsed(name, args);
    }

    /** 判断输入是否为命令（以 '/' 开头且命令名非空） */
    public static boolean isCommand(String input) {
        if (input == null || input.isEmpty()) return false;
        String trimmed = input.stripLeading();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '/') return false;
        String body = trimmed.substring(1);
        if (body.isEmpty()) return false;
        int spaceIdx = body.indexOf(' ');
        String name = spaceIdx == -1 ? body : body.substring(0, spaceIdx);
        return !name.isEmpty();
    }
}
