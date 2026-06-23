---
name: commit
description: 生成 commit message（基于 git diff --staged）
mode: inline
allowedTools:
  - exec_command
---
请根据 `git diff --staged` 的输出生成一条简洁的中文 commit message。

步骤：
1. 执行 `git diff --staged` 获取暂存区变更
2. 分析变更内容，提取关键改动
3. 按 conventional commits 规范生成 commit message，格式为：
   - feat: 新功能
   - fix: 修复
   - refactor: 重构
   - docs: 文档
   - test: 测试
   - chore: 杂项
4. 输出一条 commit message（包含标题，不超过 72 字符）
5. 如有必要，在标题后添加空行和详细说明

$ARGUMENTS
