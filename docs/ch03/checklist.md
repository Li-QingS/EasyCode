<!-- 生成时间: 2026-06-17 -->
# EasyCode 工具系统 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] [Tool] 六个工具实现存在
  （验证：mvn compile 通过，ToolRegistry size==6）
- [ ] [ToolRegistry] 注册+查找+toToolsJson 正确
  （验证：mvn test -Dtest=ToolRegistryTest 通过）
- [ ] [EditFileTool] 匹配替换+零匹配+多匹配
  （验证：mvn test -Dtest=EditFileToolTest 通过）
- [ ] [ExecCommandTool] 正常执行+超时+危险拦截
  （验证：mvn test -Dtest=ExecCommandToolTest 通过）
- [ ] [AnthropicProvider] SSE tool_use 解析
  （验证：mvn test -Dtest=AnthropicProviderToolTest 通过）
- [ ] [ConversationMgr] 窗口裁剪
  （验证：mvn test -Dtest=ConversationMgrTrimTest 通过）
- [ ] [Config] 新字段默认值
  （验证：移除 context_window 后启动，窗口=128000）

## 集成
- [ ] Config → ConversationMgr：窗口大小注入
- [ ] ToolRegistry → AnthropicProvider：tools 拼入请求体
- [ ] AnthropicProvider → onToolCall：解析正确
- [ ] TUI ToolExecutor → Tool.execute → 灌回 ConversationMgr
- [ ] 二次 chatStream：模型收到工具结果后正文回答

## 编译与测试
- [ ] mvn compile 无错误
- [ ] mvn test 全部通过
- [ ] 新增 5 个测试类全部通过

## 端到端场景
- [ ] E2E1 读文件："读一下 pom.xml"→工具调用→回答引用内容
- [ ] E2E2 编辑文件：edit_file 成功→再次读取验证
- [ ] E2E3 命令执行：ls 返回目录列表→模型据此回答
- [ ] E2E4 搜索代码：搜"main"→grep_code 返回结果
- [ ] E2E5 超时处理：工具超时后不崩溃
- [ ] E2E6 窗口裁剪：超窗口后旧消息被裁剪
