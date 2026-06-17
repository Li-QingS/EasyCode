# Agent Loop Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性

- [ ] AgentEvent 事件族完整（8 种事件类型）（验证：`mvn -q compile` 通过）
- [ ] ToolRegistry 可按权限过滤工具（验证：`mvn -q compile` 通过）
- [ ] ConversationMgr 新增 toolUse/toolResult 便捷方法（验证：`mvn -q compile` 通过；`ConversationMgrTest` 通过）
- [ ] StreamingCollector 双路收集：实时转发 + 完整累积（验证：`mvn -q compile` 通过）
- [ ] ToolExecutor 分批并发逻辑完整（验证：`mvn -q compile` 通过）
- [ ] OpenAIProvider 完整支持工具调用（验证：`mvn -q compile` 通过）
- [ ] AgentLoop 主循环完成（验证：`mvn -q compile` 通过）
- [ ] Tui 重构接入 AgentLoop 事件流（验证：`mvn -q compile` 通过）

## 功能验收

- [ ] AC1 多轮自动连环 — 连续两步工具任务自动完成，无需用户催（验证：启动程序，输入"先读 docs/ch03/spec.md，再据此新建 docs/ch03/spec-copy.txt 摘要文件"，观察自动多轮执行）
- [ ] AC2 自然完成 — 模型纯文本回复时循环立即停止（验证：输入简单问题"1+1=?"，看到直接答复无工具调用）
- [ ] AC3 迭代上限兜底 — 触顶停止不无限循环（验证：观察复杂任务在第 10 轮停止并提示，不无限循环）
- [ ] AC4 连续未知工具停止 — 模型持续请求不存在工具时停止（验证：修改模型请求的工具名，观察连续 3 次后停止并提示）
- [ ] AC5 流出错恢复 — provider 错误后不崩溃可继续对话（验证：用错误 base_url 发请求，看到错误提示后继续输入正常对话）
- [ ] AC6 事件流完备 — 界面仅靠事件渲染（验证：观察对话过程中文本增量、工具开始/结束、用量、进度全正常出现）
- [ ] AC7 流式收集双路 — 文本实时显示 + 工具调用完整收集（验证：触发多工具调用场景，文本边出边显示，同时后续工具正确执行）
- [ ] AC8 保序分批并发 — 只读并发、副作用串行、顺序保持（验证：单次请求触发 find_files + grep_code + write_file，观察前两个工具行几乎同时出现，write_file 在后，都按顺序排列）
- [ ] AC9 历史一致 — 取消/上限/出错后仍可继续对话（验证：触发迭代上限后，继续输入新问题，不再 400 报错）
- [ ] AC10 用户取消 — 流式态 Esc 中断、空闲态 Ctrl+C 退出（验证：请求中按 Esc，提示已取消，可继续输入；空闲态 Ctrl+C 退出）
- [ ] AC11 用量展示 — 状态栏显示累计 token 用量（验证：连续多轮对话，观察状态栏 input/output tokens 随轮次增长）
- [ ] AC12 进度展示 — 状态栏显示当前迭代轮次（验证：触发多轮工具调用，观察状态栏动态更新"第 N/10 轮"）
- [ ] AC13 Plan Mode — /plan 只读 + /do 全工具（验证：输入 /plan 后发复杂任务，模型只用 read/find/grep；输入 /do 后模型开始用 write/edit/exec）
- [ ] AC14 跨协议一致 — anthropic 和 openai 都跑通完整 Loop（验证：分别用两种协议配置启动，各完成一次多轮工具调用+取消测试）

## 编译与测试

- [ ] 项目编译无错误（验证：`mvn -q compile`）
- [ ] 所有现有测试通过（验证：`mvn test`）
- [ ] ToolExecutorTest 全部通过（验证：`mvn test -Dtest=ToolExecutorTest`）
- [ ] AgentLoopTest 全部通过（验证：`mvn test -Dtest=AgentLoopTest`）
- [ ] 无编译警告（验证：`mvn -q compile` 输出为空）

## 端到端场景

- [ ] **场景 1：多轮工具链** — 输入"读 pom.xml 了解项目依赖，然后在 src/main/resources/ 下创建一个 deps.txt 列出所有依赖名"，观察 Agent 自动：读 pom.xml -> 根据内容创建 deps.txt，完成后给出自然语言总结
- [ ] **场景 2：Plan Mode 完整流程** — 输入 `/plan`，再输入"用 find_files 找到所有 .java 文件，然后统计数量并用 exec 输出"，模型仅用只读工具产出计划；输入 `/do`，模型切换到全工具模式，执行之前的计划
- [ ] **场景 3：取消后继续** — 触发工具调用期间按 Esc 取消，观察提示已取消；再输入"你好"验证可正常继续对话，不报错
