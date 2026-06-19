# ch08 上下文管理 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性

- [ ] [Offloader] F1 单条落盘：构造 60000 字节工具结果 → 对话中被替换为预览体（验证：OffloaderTest.shouldOffloadSingleLargeResult）
- [ ] [Offloader] F2 聚合落盘：构造 3 条 80000 字节结果 → 按大→小落盘直到聚合 ≤ 200000（验证：OffloaderTest.shouldOffloadAggregateResults）
- [ ] [Offloader] 幂等落盘：同一 toolUseId 触发两次 → 第二次跳过 I/O（验证：OffloaderTest.shouldSkipDuplicateOffload）
- [ ] [Offloader] 预览体格式：含字节数 + 头部 ≤20行/2048字节 + 路径 + 重读提示四项（验证：OffloaderTest 检查预览体结构）
- [ ] [Offloader] 落盘失败降级：mock I/O 失败 → 该 id 不进账本，下轮可重新评估（验证：OffloaderTest.shouldRetryOnDiskFailure）
- [ ] [Offloader] F2a 原子性：落盘→改写content→写入账本串行，任一步失败三件都不发生（验证：OffloaderTest 断点测试）
- [ ] [ReplacementLedger] 决策冻结：替换后每轮用同一 String 引用（验证：ReplacementLedgerTest ID 替换后 getReplacement 返回同一引用）
- [ ] [ReplacementLedger] 保留原文也写 seenIds：决策为"不替换"的 id 也被 seen，不再翻转（验证：ReplacementLedgerTest）
- [ ] [TokenEstimator] 锚点更新：连续 3 次真实请求后锚点 = 最近一次 usage(input+cache_read+cache_creation+output)之和（验证：TokenEstimator 检查 updateAnchor 后 estimate 正确）
- [ ] [TokenEstimator] 增量估算：chars/3.5，含锚点下界（验证：estimate ≥ anchor）
- [ ] [SummaryGenerator] 摘要请求无工具：抓取请求体 tools 为空（验证：SummaryGeneratorTest 检查 tools 字段）
- [ ] [SummaryGenerator] 9部分结构：摘要内容可解析出 9 个固定小节，含 `<analysis>` 和 `<summary>` 标签（验证：模拟摘要返回检查结构）
- [ ] [SummaryGenerator] 近期保留双下界：保留 ≥10000 token 且 ≥5 条消息（验证：边界条件测试）
- [ ] [SummaryGenerator] 不拆分 tool 对：截断点不夹在 tool_use/tool_result 中间（验证：构造配对消息测试）
- [ ] [SummaryGenerator] PTL 重试：摘要请求撞 prompt_too_long → 按消息组丢弃策略重试（3次直接重试 + 20%比例丢）（验证：SummaryGeneratorTest PTL 重试用例）
- [ ] [RecoveryBuilder] 文件快照 ≤5 个：读 7 个文件 → 只展示最后 5 个（验证：RecoveryBuilderTest）
- [ ] [RecoveryBuilder] 文件快照截断：超 5000 token → 截断 + 标注 `(content truncated)`（验证：RecoveryBuilderTest）
- [ ] [RecoveryBuilder] 工具列表同引用：与紧随其后的 LLM 请求 tools 参数同一引用（验证：`==` 测试）
- [ ] [RecoveryBuilder] 边界提示固定文案：含"需要原文请重读，不要靠摘要猜测"（验证：RecoveryBuilderTest 检查文案）
- [ ] [ContextManager] 自动触发阈值：token 跨过 `contextWindow-33000` → 触发摘要（验证：ContextManagerTest 边界测试）
- [ ] [ContextManager] 手动跳过阈值：token 远低于阈值时 `/compact` 仍触发（验证：manualCompact 无阈值检查）
- [ ] [ContextManager] 手动跳过熔断：自动连续失败 3 次后 `/compact` 仍正常（验证：构造熔断状态测 manualCompact）
- [ ] [ContextManager] 熔断：连续 3 次自动摘要失败 → 第 4 次不触发（验证：ContextManagerTest）
- [ ] [ContextManager] 熔断恢复：失败后一次成功 → 计数清零（验证：recordSuccess 后 tripped==false）
- [ ] [ContextManager] 三入口互斥：manualCompact 与 autoManage 不能并发（验证：ReentrantLock 锁测试）
- [ ] [TUI] 命令路由：输入 `/compact` → 不触发 LLM 对话请求（验证：手动启动后输入 /compact）
- [ ] [TUI] 未知命令兜底：输入 `/unknown` → 友好提示，不发 LLM（验证：手动启动后输入 /unknown）
- [ ] [TUI] 已有命令迁移：`/exit`、`/plan`、`/do`、`/perm` 在 BUILTIN_COMMANDS 下，行为不变（验证：手动各命令）
- [ ] [TUI] 自动压缩提示：自动触发时显示"正在压缩上下文..."+"已压缩，token 从 X 降至 Y"（验证：手动观察）
- [ ] [TUI] 紧急压缩提示：紧急触发时显示"上下文撞墙，自动压缩中..."（验证：手动观察）
- [ ] [AgentLoop] 紧急压缩：mock provider 返回 prompt_too_long → 触发 emergencyCompact → 重试一次（验证：AgentLoopTest）
- [ ] [AgentLoop] 紧急压缩不重复：重试再 PTL → 不二次重试（验证：AgentLoopTest）
- [ ] [AgentLoop] 紧急压缩后重估算：紧急压缩成功后用新消息+重置锚点重估算，不满足余量则上抛（验证：AgentLoopTest）
- [ ] [AgentLoop] 文件追踪触发：ReadFile 成功后同步记录到 FileTracker（验证：AgentLoopTest 检查 FileTracker 状态）
- [ ] [Config] contextWindow 默认值：anthropic→200000, openai→128000, 未配置→默认（验证：ConfigLoaderTest）
- [ ] [Config] contextWindow 覆盖：配置 100000 → 自动阈值 67000（验证：ConfigLoaderTest）
- [ ] [配置示例] easycode.yaml 包含 context_window 字段的注释说明（验证：grep context_window easycode.yaml）
- [ ] [SessionManager] 会话目录：进程启动后 `.EasyCode/sessions/<unix_ts>-<short_random>` 子目录存在（验证：文件系统检查）
- [ ] [旧代码清理] ToolResultTruncator.java 已删除，无残留引用（验证：grep ToolResultTruncator src/）

## 非功能验证

- [ ] 并发安全-账本：多线程同时调 ledger.decide() → 无数据竞争（验证：ReplacementLedgerTest 并发用例）
- [ ] 并发安全-文件追踪：多线程 record + recentSnapshots → 无数据竞争（验证：FileTracker 并发用例）
- [ ] 并发安全-熔断计数：auto/manual 同时操作 → 计数一致性（验证：ContextManagerTest 并发用例）
- [ ] 错误隔离-落盘降级：磁盘满时 offload → 工具结果保持原文（验证：OffloaderTest 降级测试）
- [ ] 错误隔离-摘要失败不崩溃：provider 异常时摘要失败 → Agent 进程不退出（验证：SummaryGenerator 异常捕获）
- [ ] 性能-第1层：处理 50 条工具结果的历史 → < 10ms（验证：Offloader 性能基准观察）

## 编译与测试

- [ ] `mvn -q -DskipTests compile` 通过，无编译错误
- [ ] `mvn test` 全部通过，Failures: 0, Errors: 0
- [ ] `mvn -q -DskipTests package` 打包成功

## 端到端场景

- [ ] **场景 1——长时间工作不溢出**：模拟 20 轮对话，每轮读 100KB 文件 → 第 1 层自动截断大结果，第 2 层自动摘要 → 全程无 prompt_too_long（验证：E2E 测试）

- [ ] **场景 2——手动压缩**：启动 EasyCode，对话几轮后输入 `/compact` → 显示 "正在压缩上下文..." → 完成后显示 "已压缩，token 从 X 降至 Y"（验证：手动观察）

- [ ] **场景 3——紧急压缩**：mock provider 在第 3 轮返回 prompt_too_long → TUI 显示 "上下文撞墙，自动压缩中..." → 压缩后重试成功（验证：E2E 测试）

- [ ] **场景 4——熔断恢复**：自动摘要连续失败 3 次 → 第 4 次不触发自动摘要 → `/compact` 仍正常执行（验证：E2E 测试）

- [ ] **场景 5——恢复段可用**：摘要后模型被问到"刚才读的文件内容" → 恢复段中有该文件 → 模型正确引用或显式重读（验证：手动对话观察）

- [ ] **场景 6——缓存稳定性**：同一 toolUseId 被替换后，此后每轮该位置的预览字符串逐字节相同，不破坏 prompt cache（验证：ReplacementLedger 快照对比）
