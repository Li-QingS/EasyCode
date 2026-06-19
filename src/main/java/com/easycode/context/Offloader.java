package com.easycode.context;

import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 第 1 层压缩（F1+F2）：单条超阈值 + 单轮聚合超阈值落盘。
 * 替换旧的 ToolResultTruncator。
 */
public final class Offloader {

    private Offloader() {}

    /**
     * 对 conversation 中所有 RoleTool 消息执行 F1+F2 联合扫描。
     * @return 被替换的 toolUseId 数量。
     */
    public static int offloadAndSnip(ConversationMgr conv, ReplacementLedger ledger, Path sessionDir) {
        var history = conv.getHistory();
        int replaced = 0;

        for (int i = 0; i < history.size(); i++) {
            MessageRecord msg = history.get(i);
            if (!isRoleTool(msg)) continue;

            var blocks = new ArrayList<>(msg.blocks());
            var candidates = new ArrayList<ToolResultEntry>();
            for (int j = 0; j < blocks.size(); j++) {
                MessageBlock b = blocks.get(j);
                if (b instanceof MessageBlock.ToolResultBlock tr) {
                    candidates.add(new ToolResultEntry(j, tr.toolUseId(), tr.content(), tr.isError()));
                }
            }
            if (candidates.isEmpty()) continue;

            // 按字节倒序
            candidates.sort(Comparator.<ToolResultEntry>comparingLong(e -> e.byteLen).reversed());

            boolean msgChanged = false;
            long aggregateBytes = 0;

            // ---------- F1：单条超阈值 ----------
            for (ToolResultEntry e : candidates) {
                if (e.handled) continue;
                if (e.byteLen > Constants.SINGLE_RESULT_THRESHOLD_BYTES) {
                    String preview = ledger.decide(e.toolUseId, e.content, raw -> buildPreview(raw, e, sessionDir));
                    if (preview != null && preview != e.content) {
                        blocks.set(e.index, new MessageBlock.ToolResultBlock(e.toolUseId, preview, e.isError));
                        e.handled = true;
                        e.replaced = true;
                        msgChanged = true;
                        replaced++;
                    }
                }
                if (!e.handled && !e.replaced) {
                    aggregateBytes += e.byteLen;
                }
            }

            // ---------- F2：单轮聚合超阈值 ----------
            if (aggregateBytes > Constants.MESSAGE_AGGREGATE_THRESHOLD_BYTES) {
                for (ToolResultEntry e : candidates) {
                    if (e.handled) continue;
                    String preview = ledger.decide(e.toolUseId, e.content, raw -> buildPreview(raw, e, sessionDir));
                    if (preview != null && preview != e.content) {
                        blocks.set(e.index, new MessageBlock.ToolResultBlock(e.toolUseId, preview, e.isError));
                        e.handled = true;
                        e.replaced = true;
                        msgChanged = true;
                        replaced++;
                    }
                    aggregateBytes -= e.byteLen;
                    if (aggregateBytes <= Constants.MESSAGE_AGGREGATE_THRESHOLD_BYTES) break;
                }
            }

            if (msgChanged) {
                conv.replaceMessage(i, new MessageRecord(msg.role(), msg.content(), blocks));
            }
        }
        return replaced;
    }

    // ---------- 预览体构造（F4） ----------

    private static String buildPreview(String rawContent, ToolResultEntry e, Path sessionDir) {
        byte[] bytes = rawContent.getBytes(StandardCharsets.UTF_8);
        int byteLen = bytes.length;

        // 头部预览：前 20 行或前 2048 字节择短
        String head = headPreview(rawContent);

        // 落盘
        Path filePath = sessionDir.resolve(e.toolUseId);
        try {
            Files.createDirectories(sessionDir);
            Files.writeString(filePath, rawContent);
        } catch (IOException ioe) {
            // 落盘失败 → 返回 null，不替换
            java.lang.System.err.println("[context] offload failed for " + e.toolUseId + ": " + ioe.getMessage());
            return null;
        }

        return String.format(
            "[原始 %d 字节]\n%s\n\n完整内容已保存至: %s\n用 read_file 读取完整内容。",
            byteLen, head, filePath.toString()
        );
    }

    private static String headPreview(String content) {
        // 先按行截到 20 行
        String[] lines = content.split("\n", 21);
        StringBuilder sb = new StringBuilder();
        int lineCount = Math.min(lines.length, Constants.PREVIEW_HEAD_LINES_MAX);
        for (int i = 0; i < lineCount; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        String lineCapped = sb.toString();
        // 再按字节截到 2048
        byte[] bytes = lineCapped.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= Constants.PREVIEW_HEAD_BYTES_MAX) return lineCapped;
        // 截断到 2048 字节，避免切断多字节字符
        String byteCapped = new String(bytes, 0, Constants.PREVIEW_HEAD_BYTES_MAX, StandardCharsets.UTF_8);
        // 去掉末尾可能被截断的不完整字符
        int lastValid = byteCapped.length() - 1;
        while (lastValid >= 0 && Character.isSurrogate(byteCapped.charAt(lastValid))) lastValid--;
        return byteCapped.substring(0, lastValid + 1);
    }

    // ---------- 辅助 ----------

    private static boolean isRoleTool(MessageRecord msg) {
        if (msg.role() != Role.USER) return false;
        for (MessageBlock b : msg.blocks()) {
            if (b instanceof MessageBlock.ToolResultBlock) return true;
        }
        return false;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 工具结果候选条目，按字节倒序排序 */
    private static class ToolResultEntry {
        final int index;
        final String toolUseId;
        final String content;
        final boolean isError;
        final long byteLen;
        boolean handled;
        boolean replaced;

        ToolResultEntry(int index, String toolUseId, String content, boolean isError) {
            this.index = index;
            this.toolUseId = toolUseId;
            this.content = content;
            this.isError = isError;
            this.byteLen = content.getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
