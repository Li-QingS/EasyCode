package com.easycode.provider;

import com.fasterxml.jackson.databind.JsonNode;

/** 从 SSE 流解析出的工具调用 */
public record ToolCall(
    String id,
    String name,
    JsonNode input
) {}
