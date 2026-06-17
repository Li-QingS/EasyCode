package com.easycode.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.easycode.conversation.MessageRecord;
import com.easycode.provider.Request;
import java.util.List;

/** LLM 后端统一抽象接口 */
public interface LlmProvider {
    void chatStream(Request request, StreamHandler handler);
}
