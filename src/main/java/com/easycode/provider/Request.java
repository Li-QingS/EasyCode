package com.easycode.provider;

import com.easycode.conversation.MessageRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 一次 LLM 请求的完整入参 */
public record Request(
    List<MessageRecord> messages,
    List<JsonNode> tools,
    System system,
    String reminder
) {}
