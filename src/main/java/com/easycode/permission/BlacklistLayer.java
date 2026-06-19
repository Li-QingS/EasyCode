package com.easycode.permission;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import java.util.List;
import java.util.regex.Pattern;

public final class BlacklistLayer implements PermissionLayer {

    private static final List<Pattern> BLACKLIST = List.of(
        Pattern.compile("rm\\s+-rf\\s+/"),
        Pattern.compile("rm\\s+-rf\\s+~"),
        Pattern.compile("rm\\s+-rf\\s+\\$HOME"),
        Pattern.compile("mkfs"),
        Pattern.compile("dd\\s+if=.*of=/dev/"),
        Pattern.compile(">\\s*/dev/sd")
    );

    @Override
    public PermissionResult check(ToolCall call, Tool tool, PermissionContext ctx) {
        if (!"exec_command".equals(tool.name())) return PermissionResult.NOT_APPLICABLE;
        String cmd = call.input().has("command") ? call.input().get("command").asText() : "";
        for (Pattern p : BLACKLIST) {
            if (p.matcher(cmd).find()) return PermissionResult.DENY;
        }
        return PermissionResult.NOT_APPLICABLE;
    }

    @Override
    public String deniedReason(ToolCall call, Tool tool, PermissionContext ctx) {
        return "危险命令被黑名单拦截";
    }
}
