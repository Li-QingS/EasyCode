package com.easycode.mcp;

import java.util.List;
import java.util.Map;

public record McpServerConfig(
    String type, String command, List<String> args,
    Map<String, String> env, String url, Map<String, String> headers
) {}
