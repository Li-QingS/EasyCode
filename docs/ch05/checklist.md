# 系统提示工程化 Checklist

## 实现完整性
- [ ] Module/Prompt/Environment/Reminder 四个类完整（验证: mvn compile）
- [ ] Request/System record + StreamHandler/LlmProvider 签名变更（验证: mvn compile）
- [ ] AnthropicProvider system数组+cache_control+reminder织入（验证: mvn compile）
- [ ] OpenAIProvider system前缀+reminder注入+cached_tokens解析（验证: mvn compile）
- [ ] AgentLoop 环境采集/system装配/reminder按轮次（验证: mvn compile）
- [ ] 工具描述双重强化（验证: mvn compile）

## 功能验收
- [ ] AC1 模块化装配 — 7固定模块按priority排列，"\n\n"分隔
- [ ] AC2 空槽跳过 — 可选模块content=""时跳过
- [ ] AC3 环境信息 — 含工作目录、平台、日期、Shell
- [ ] AC4 缓存命中 — 次轮 cacheRead > 0
- [ ] AC5 缓存确定性 — buildStable() 逐字节一致
- [ ] AC6 缓存字段解析 — 缺失时值为0不报错
- [ ] AC7 双重强化 — 工具描述+system同时出现关键约定
- [ ] AC8 补充消息注入 — <system-reminder>不写conversation
- [ ] AC9 规划模式按轮次 — 首轮完整/周期4
- [ ] AC10 跨协议一致 — anthropic+openai行为一致
- [ ] AC11 不破坏ch04 — mvn test全部通过
- [ ] AC12 历史合法 — 注入reminder后不400
- [ ] AC13 环境采集降级 — 非git目录省略Git行
- [ ] AC14 代码规范 — mvn spotless:check通过

## 编译与测试
- [ ] mvn -q compile 通过
- [ ] mvn test 全部通过
- [ ] PromptTest / EnvironmentTest / ReminderTest / AnthropicSystemTest 通过

## 端到端
- [ ] 场景1: 缓存命中 — 连续两轮请求，次轮cacheRead>0
- [ ] 场景2: Plan Mode reminder节奏 — /plan后第1条完整/第2-4条精简/第5条完整
- [ ] 场景3: 工具描述强化 — 问"怎么读文件"→优先用read_file而非exec_command
