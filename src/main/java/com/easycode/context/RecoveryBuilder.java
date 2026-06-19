package com.easycode.context;

import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.context.FileTracker.FileSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 压缩后恢复段构造（F15-F18）。产出三段消息：文件快照 + 工具列表 + 边界提示。
 */
public final class RecoveryBuilder {

    private static final String BOUNDARY_HINT =
        "以下是历史摘要，如需文件原文、错误原文或用户原话，请用文件读取工具重新读取，不要根据摘要脑补代码。";

    private RecoveryBuilder() {}

    /**
     * 构建恢复段。
     * @param tracker 文件追踪状态
     * @param tools   当前可用工具定义（与 LLM 请求 tools 参数同一引用，F17）
     * @return 恢复段消息列表（插入在摘要之后、近期原文之前）
     */
    public static List<MessageRecord> build(FileTracker tracker, List<JsonNode> tools) {
        return List.of(new MessageRecord(Role.USER, buildAsString(tracker, tools)));
    }

    /** 构建恢复段并返回合并后的纯文本（用于拼入摘要 USER 消息） */
    public static String buildAsString(FileTracker tracker, List<JsonNode> tools) {
        StringBuilder sb = new StringBuilder();

        // ---- 第 1 段：最近读过的文件快照（F16） ----
        sb.append("## 最近读过的文件\n\n");
        List<FileSnapshot> snapshots = tracker.recentSnapshots(Constants.MAX_RECENT_FILES);
        if (snapshots.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (FileSnapshot snap : snapshots) {
                sb.append("### ").append(snap.path()).append("\n");
                sb.append("读取时间: ").append(new java.util.Date(snap.readTime())).append("\n\n");
                String content = snap.content();
                int charLimit = (int) (Constants.FILE_SNAPSHOT_TOKEN_MAX * Constants.ESTIMATE_CHARS_PER_TOKEN);
                if (content.length() > charLimit) {
                    content = content.substring(0, charLimit) + "\n(content truncated)";
                }
                sb.append("```\n").append(content).append("\n```\n\n");
            }
        }

        // ---- 第 2 段：当前可用工具列表（F17） ----
        sb.append("## 当前可用工具\n\n");
        for (JsonNode tool : tools) {
            String name = tool.has("name") ? tool.get("name").asText() : "?";
            String desc = tool.has("description") ? tool.get("description").asText() : "";
            if (desc.length() > 120) desc = desc.substring(0, 120) + "...";
            sb.append("- **").append(name).append("**: ").append(desc).append("\n");
        }
        sb.append("\n");

        // ---- 第 3 段：边界提示（F18） ----
        sb.append(BOUNDARY_HINT).append("\n");

        return sb.toString();
    }
}
