package com.easycode.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.easycode.conversation.MessageRecord;
import java.util.List;

/** LLM 后端统一抽象接口 */
public interface LlmProvider {
    void chatStream(List<MessageRecord> history, List<JsonNode> tools, StreamHandler handler);
}
