---
type: project_knowledge
title: EasyCode 内置工具与 Tool 接口
created: 2026-06-20T18:13:48.282079142+08:00
updated: 2026-06-20T18:13:48.282079142+08:00
---

项目包含6个内置工具，位于 src/main/java/com/easycode/tool/：ReadFileTool、WriteFileTool、EditFileTool、ExecCommandTool、FindFilesTool、GrepCodeTool。每个工具实现 Tool 接口，需提供 name()、description()、inputSchema()、execute(JsonNode)、category()，可选 permission()、requiresApproval()、isDestructive()、defaultState()。
