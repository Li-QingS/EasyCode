package com.easycode.permission;

import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;

public final class PermissionPipeline {

    private final BlacklistLayer blacklist = new BlacklistLayer();
    private final SandboxLayer sandbox = new SandboxLayer();
    private final ModeDeductionLayer modeDeduction = new ModeDeductionLayer();
    private final RuleEngine ruleEngine;
    private final PermissionMode startMode;

    public PermissionPipeline(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
        this.startMode = (ruleEngine.defaultMode() != null)
            ? PermissionMode.valueOf(ruleEngine.defaultMode().toUpperCase())
            : PermissionMode.DEFAULT;
    }

    public PermissionMode startMode() { return startMode; }

    public PermissionResult check(ToolCall call, Tool tool, PermissionContext ctx) {
        // ① Blacklist
        PermissionResult r = blacklist.check(call, tool, ctx);
        if (r == PermissionResult.DENY || r == PermissionResult.ALLOW) return r;

        // ② Sandbox
        r = sandbox.check(call, tool, ctx);
        if (r == PermissionResult.DENY || r == PermissionResult.ALLOW) return r;

        // ③ RuleEngine
        Boolean ruleResult = ruleEngine.match(call, tool);
        if (ruleResult != null) return ruleResult ? PermissionResult.ALLOW : PermissionResult.DENY;

        // ④ ModeDeduction
        return modeDeduction.check(call, tool, ctx);
    }
}
