package com.easycode.subagent;

import java.util.List;

/** Agent 角色定义 */
public record AgentDef(
    String name,
    String description,
    String systemPrompt,
    List<String> toolsAllow,
    List<String> toolsDeny,
    String model,
    int maxTurns,
    String permission,
    String isolation,
    int timeoutSec
) {
    public AgentDef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (systemPrompt == null) systemPrompt = "";
        if (toolsAllow == null) toolsAllow = List.of();
        if (toolsDeny == null) toolsDeny = List.of();
        if (model == null) model = "";
        if (maxTurns <= 0) maxTurns = 10;
        if (permission == null) permission = "";
        if (isolation == null) isolation = "none";
        if (timeoutSec <= 0) timeoutSec = 120;
    }
}
