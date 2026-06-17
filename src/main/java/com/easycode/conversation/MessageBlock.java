package com.easycode.conversation;

import com.fasterxml.jackson.databind.JsonNode;

/** 消息块 sealed interface，支持纯文本、工具调用、工具结果三种类型 */
public sealed interface MessageBlock
        permits MessageBlock.TextBlock, MessageBlock.ToolUseBlock, MessageBlock.ToolResultBlock {

    record TextBlock(String text) implements MessageBlock {}

    record ToolUseBlock(String id, String name, JsonNode input) implements MessageBlock {}

    record ToolResultBlock(String toolUseId, String content, boolean isError) implements MessageBlock {}
}
