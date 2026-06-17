<!-- 生成时间: 2026-06-17 -->
# EasyCode 纯对话阶段 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性

- [ ] [Config] 配置加载已实现，缺失/非法字段时输出错误并退出
  （验证：故意写错 easycode.yaml，启动程序，观察错误输出）
- [ ] [LlmProvider] 接口已定义，两个后端实现类存在
  （验证：mvn compile 通过，两个实现类可实例化）
- [ ] [AnthropicProvider] SSE 流解析正确，extended_thinking 启用时 thinking token 正常回调
  （验证：配置 anthropic + claude 模型，启动后对话，观察流式输出）
- [ ] [OpenAIProvider] SSE 流解析正确，stream: true 生效
  （验证：配置 openai 模型，启动后对话，观察流式输出）
- [ ] [ConversationMgr] 消息增删查逻辑正确，getHistory() 返回不可变副本
  （验证：mvn test -Dtest=ConversationMgrTest 通过）
- [ ] [TUI] JLine 交互界面可用，支持输入、多行、特殊命令
  （验证：启动程序，看到欢迎信息和提示符，输入文本有响应）
- [ ] [Main] 启动流程串联正确
  （验证：配置正确后启动程序能进入 TUI 循环）

## 集成

- [ ] Config → ProviderFactory：根据 protocol 字段正确创建对应 Provider
  （验证：切换 easycode.yaml 的 protocol，重启程序后行为切换）
- [ ] ConversationMgr → Provider：完整对话历史正确传入 chatStream()
  （验证：多轮对话后检查 Provider 收到的 messages 数量逐轮递增）
- [ ] Provider → TUI：流式 token 实时打印，不积攒
  （验证：视觉观察 token 逐个出现，不是一次性输出）
- [ ] TUI → ConversationMgr：用户输入和助手回复均正确写入历史
  （验证：对话后检查 ConversationMgr.getHistory() 包含完整记录）
- [ ] ProviderFactory 未知 protocol 抛异常
  （验证：mvn test -Dtest=ProviderFactoryTest 通过）

## 编译与测试

- [ ] mvn compile 无错误
- [ ] 所有单元测试 mvn test 通过
- [ ] mvn package 打包成功，生成 fat jar

## 端到端场景

- [ ] E2E1：配置文件 protocol: anthropic，启动 EasyCode → 看到欢迎信息 → 输入「你好」 → 看到 AI 逐字流式回复 → 输入「刚才我说了什么？」 → AI 能引用上一轮内容 → 输入 /exit → 程序正常退出
- [ ] E2E2：配置文件 protocol: openai，启动 EasyCode → 输入问题 → 看到流式回复 → 输入 /help → 显示命令列表 → 输入 /exit → 退出
- [ ] E2E3：配置文件缺少 api_key → 启动程序 → 看到明确错误提示 → 程序退出（非崩溃）
- [ ] E2E4：API 返回 401（如 api_key 无效） → 程序显示可读错误信息 → 不崩溃，继续允许新输入
