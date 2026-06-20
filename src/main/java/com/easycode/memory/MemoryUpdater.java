package com.easycode.memory;

import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.provider.LlmProvider;
import com.easycode.provider.Request;
import com.easycode.provider.StreamHandler;
import com.easycode.provider.System;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class MemoryUpdater {
    private static final ObjectMapper json = new ObjectMapper();
    private static final int TIMEOUT_SEC = 60;
    private static MemoryStore projectStore;
    private static MemoryStore userStore;

    private MemoryUpdater() {}

    public static void updateAsync(LlmProvider provider, List<MessageRecord> recentMsgs,
            MemoryStore projStore, MemoryStore userStore) {
        if (provider == null) return;
        projectStore = projStore;
        MemoryUpdater.userStore = userStore;
        new Thread(() -> {
            try {
                String projIdx = projStore.readIndex();
                String userIdx = userStore.readIndex();
                String prompt = buildPrompt(recentMsgs, projIdx, userIdx);
                String response = callLlm(provider, prompt);
                if (response == null || response.isBlank()) return;
                applyOperations(response, projStore, userStore);
            } catch (Exception e) {
                java.lang.System.err.println("[memory] update failed: " + e.getMessage());
            }
        }, "memory-updater").start();
    }

    private static String buildPrompt(List<MessageRecord> msgs, String projIdx, String userIdx) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个对话记忆提取助手。分析最近对话，提取值得长期记忆的信息。\n\n");
        sb.append("操作:\n");
        sb.append("- create: 创建新笔记(level, type, title, slug, content)。已有相似笔记时改用 update\n");
        sb.append("- update: 更新已有笔记(level, filename, title, content)\n");
        sb.append("- delete: 删除过期笔记(level, filename)\n\n");
        sb.append("类型: user_preference, correction_feedback, project_knowledge, reference_material\n\n");
        sb.append("规则:\n");
        sb.append("- 已有相同含义的记忆不要重复创建。检查现有笔记标题，类似条目用 update\n");
        sb.append("- 不同 slug 但内容相同就是重复，应 update 而非 create\n");
        sb.append("- 没有值得记忆的内容返回空数组 []\n");
        sb.append("- 禁止调用工具，只输出 JSON\n\n");
        sb.append("=== 项目记忆 ===\n").append(projIdx.isBlank()?"(空)":projIdx).append("\n\n");
        sb.append("=== 用户记忆 ===\n").append(userIdx.isBlank()?"(空)":userIdx).append("\n\n");
        sb.append("=== 现有笔记完整标题（用于去重）===\n");
        appendTitles(sb, projIdx, "项目级");
        appendTitles(sb, userIdx, "用户级");
        sb.append("\n=== 最近对话 ===\n");
        for (var m : msgs) {
            sb.append("[").append(m.role()).append("]: ");
            if (m.content() != null && !m.content().isBlank()) sb.append(m.content());
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void appendTitles(StringBuilder sb, String index, String label) {
        sb.append(label).append(":\n");
        if (index == null || index.isBlank()) { sb.append("  (空)\n"); return; }
        for (String line : index.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) sb.append("  ").append(t).append("\n");
        }
    }

    private static String callLlm(LlmProvider provider, String prompt) throws Exception {
        var msgs = List.of(new MessageRecord(Role.USER, prompt));
        var req = new Request(msgs, List.of(), new System("你是记忆提取助手。只输出 JSON，禁止调用工具。", ""), "");
        CompletableFuture<String> future = new CompletableFuture<>();
        provider.chatStream(req, new StreamHandler() {
            final StringBuilder text = new StringBuilder();
            @Override public void onToken(String t) { text.append(t); }
            @Override public void onComplete() { future.complete(text.toString().trim()); }
            @Override public void onError(Exception e) { future.completeExceptionally(e); }
            @Override public void onUsage(int i, int o, int cw, int cr) {}
        });
        return future.get(TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    private static void applyOperations(String response, MemoryStore proj, MemoryStore user) {
        try {
            String cleaned = extractJson(response);
            if (cleaned.isEmpty()) { java.lang.System.err.println("[memory] no JSON in response"); return; }
            JsonNode arr = json.readTree(cleaned);
            if (!arr.isArray()) return;
            for (JsonNode op : arr) {
                String action = op.path("action").asText();
                String level = op.path("level").asText("project");
                MemoryStore store = "user".equals(level) ? user : proj;
                try {
                    switch (action) {
                        case "create" -> store.create(
                            op.path("type").asText(), op.path("slug").asText(),
                            op.path("title").asText(), op.path("content").asText());
                        case "update" -> store.update(
                            op.path("filename").asText(), op.path("title").asText(null),
                            op.path("content").asText(null));
                        case "delete" -> store.delete(op.path("filename").asText());
                    }
                } catch (Exception e) { java.lang.System.err.println("[memory] op failed: " + e.getMessage()); }
            }
        } catch (Exception e) { java.lang.System.err.println("[memory] parse failed: " + e.getMessage()); }
    }

    private static String extractJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (start > 0 && end > start) s = s.substring(start + 1, end).trim();
        }
        int arrStart = s.indexOf('[');
        int arrEnd = s.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) return s.substring(arrStart, arrEnd + 1);
        int objStart = s.indexOf('{');
        int objEnd = s.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) return s.substring(objStart, objEnd + 1);
        return "";
    }
}
