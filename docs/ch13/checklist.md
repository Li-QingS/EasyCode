# 子 Agent 系统 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] `subagent/` 包下 6 个源文件全部存在且编译通过（验证：`mvn compile -q`）
- [ ] `Main.java` 中 AgentDefLoader 和 TaskManager 初始化（验证：启动日志含 "loaded N agent definitions"）
- [ ] `AgentLoop.java` 暴露 getProvider()/getConfig()/getHookEngine() 三个 getter
- [ ] `RunAgentTool` 注册到 ToolRegistry（验证：grep tools.toToolsJson 含 run_agent）

## 集成
- [ ] SubAgent 的 filteredTools 不包含 run_agent
- [ ] Fork 式 SubAgent 的 seedMessages 与父对话历史一致

## 编译与测试
- [ ] `mvn compile -q` 无错误无警告
- [ ] `mvn test` 全部通过

## 端到端场景
- [ ] AC1：reviewer 角色只调 read_file/grep_code
- [ ] AC2：Fork 子 Agent 输出含父对话历史关键信息
- [ ] AC3：run_agent 嵌套被拦截
- [ ] AC4：Fork 超时自动切后台
- [ ] AC5：3 个后台任务并行追踪正确
- [ ] AC6：tools_deny 禁止 exec_command 生效
- [ ] AC7：项目级覆盖用户级同名 Agent
