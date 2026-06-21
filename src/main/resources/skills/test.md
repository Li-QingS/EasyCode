---
name: test
description: 运行测试并修复失败的用例
mode: fork
context: none
allowedTools:
  - read_file
  - exec_command
  - write_file
  - edit_file
  - grep_code
---
请运行项目测试并修复所有失败的测试用例。

步骤：
1. 执行 `mvn test` 运行测试
2. 分析失败的测试用例
3. 阅读相关源码，定位失败原因
4. 修改代码修复问题
5. 重新运行测试验证修复
6. 重复直到所有测试通过

$ARGUMENTS

如果未指定参数，运行全部测试。
