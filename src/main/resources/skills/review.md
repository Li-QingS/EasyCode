---
name: review
description: 代码审查（检查 bug、安全隐患、性能问题、代码异味）
mode: fork
context: recent
allowedTools:
  - read_file
  - grep_code
  - exec_command
---
请审查当前代码变更，找出潜在问题并按严重程度排序。

审查维度：
1. **Bug**：逻辑错误、边界条件遗漏、空指针风险
2. **安全隐患**：注入风险、敏感信息泄露、权限问题
3. **性能问题**：不必要的循环、大对象创建、IO 阻塞
4. **代码异味**：重复代码、过长方法、过度耦合、命名不当

对每个问题：
- 说明位置（文件名和大致行号范围）
- 描述问题原因
- 给出修复方案

审查范围：
$ARGUMENTS

如果未指定范围，审查 `git diff` 的所有变更。
