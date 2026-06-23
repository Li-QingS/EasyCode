# Worktree 隔离 Spec

## 背景
当前子 Agent 系统（ch13）已实现独立上下文和受限工具集，但所有 Agent 共享同一个文件系统，存在覆盖冲突风险。Git Worktree 提供同一仓库下多隔离工作目录机制，天然适合解决。

## 目标
- AgentDef 新增 `isolation: worktree` 字段
- run_agent 创建子 Agent 时自动创建独立 Git Worktree
- 子 Agent 所有文件操作限定在 Worktree 内
- 完成后按变更情况保留或清理
- 目录名严格安全校验
- 定期清理过期临时 Worktree

## 功能需求

- **F1：** AgentDef 新增 `isolation` 字段（`"none"` 默认，`"worktree"` 隔离）
- **F2：** Worktree 创建：`git worktree add .easycode/worktrees/<slug>/ easycode/<slug>`
- **F3：** slug 安全校验：`[a-z0-9][a-z0-9-]*`，最多64字符，拒绝 `.` `..`
- **F4：** 环境初始化：复制配置/Git hooks/补忽略文件
- **F5：** 路径映射：显式 cwd 传参，绝对路径 key 隔离缓存
- **F6：** 复用已存在目录，不重复创建
- **F7：** 退出时三层判断：无变更→清理；未提交→保留；未推送→保留
- **F8：** 过期清理：24h 未活动 Worktree 执行清理
- **F9：** 工具路径重定向：filteredTools 注入 worktreeRoot

## 非功能需求
- **N1：** 创建 < 2s
- **N2：** 不复制 .git
- **N3：** slug 校验在 IO 之前
- **N4：** 删除不误伤

## 不做的事
- Worktree 间合并策略、跨目录同步、多 Agent 并行编排、跨会话持久化

## 验收标准
- **AC1：** isolation=worktree，write_file 写入 Worktree 目录
- **AC2：** 无变更自动删除 Worktree
- **AC3：** 有变更保留并提示
- **AC4：** 非法 slug 拒绝创建
- **AC5：** 目录已存在时复用
- **AC6：** isolation=none 不创建 Worktree
