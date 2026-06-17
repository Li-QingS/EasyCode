<!-- 生成时间: 2026-06-17 -->
# EasyCode 纯对话阶段 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 改造 | pom.xml | 移除 ASM，添加 JLine/Jackson/JUnit，配置 shade |
| 新建 | src/main/java/com/easycode/Main.java | 主入口，串联启动流程 |
| 新建 | src/main/java/com/easycode/config/ConfigLoader.java | 加载校验 YAML 配置 |
| 新建 | src/main/java/com/easycode/provider/LlmProvider.java | LLM 抽象接口 |
| 新建 | src/main/java/com/easycode/provider/StreamHandler.java | 流回调接口 |
| 新建 | src/main/java/com/easycode/provider/ProviderFactory.java | 根据 protocol 创建 Provider |
| 新建 | src/main/java/com/easycode/provider/AnthropicProvider.java | Anthropic Messages API |
| 新建 | src/main/java/com/easycode/provider/OpenAIProvider.java | OpenAI Chat Completions API |
| 新建 | src/main/java/com/easycode/conversation/ConversationMgr.java | 对话历史管理 |
| 新建 | src/main/java/com/easycode/tui/Tui.java | JLine 交互界面 |
| 新建 | easycode.yaml.example | 配置模板 |
| 新建 | src/test/java/com/easycode/config/ConfigLoaderTest.java | 配置加载测试 |
| 新建 | src/test/java/com/easycode/conversation/ConversationMgrTest.java | 会话管理测试 |
| 新建 | src/test/java/com/easycode/provider/ProviderFactoryTest.java | Provider 工厂测试 |
| 删除 | src/main/java/com/easycode/agent/ | 旧 Java Agent 代码 |

## T1：改造 pom.xml

**文件：** pom.xml
**依赖：** 无
**步骤：**
1. 移除 ASM 依赖和 maven-jar-plugin 的 manifestEntries
2. 添加依赖：org.jline:jline:3.27.1、jackson-databind、jackson-dataformat-yaml
3. 添加 junit-jupiter（test scope），配置 maven-surefire-plugin
4. 更新 name/description
5. 添加 maven-shade-plugin，主类指向 com.easycode.Main
**验证：** mvn compile 通过

## T2：定义核心类型

**文件：** conversation/Message.java, provider/StreamHandler.java
**依赖：** T1
**步骤：**
1. 创建 Role 枚举（USER, ASSISTANT）和 Message record
2. 创建 StreamHandler 函数式接口（onToken, onComplete, onError）
**验证：** mvn compile 通过

## T3：实现 Config 模块

**文件：** config/ConfigLoader.java
**依赖：** T1
**步骤：**
1. 定义 Config record（protocol, model, baseUrl, apiKey）
2. 实现 ConfigLoader.load()——Jackson YAML 解析 + 校验
**验证：** mvn compile 通过

## T4：定义 LlmProvider 接口

**文件：** provider/LlmProvider.java
**依赖：** T2
**步骤：**
1. 定义 void chatStream(List<Message>, StreamHandler)
**验证：** mvn compile 通过

## T5：实现 AnthropicProvider

**文件：** provider/AnthropicProvider.java
**依赖：** T2, T3, T4
**步骤：**
1. 构造接收 Config，创建 HttpClient
2. 实现 chatStream()：构造 Anthropic Messages JSON → POST → SSE 逐行解析 → 回调
3. 自动启用 extended_thinking（claude 模型时）
**验证：** mvn compile 通过

## T6：实现 OpenAIProvider

**文件：** provider/OpenAIProvider.java
**依赖：** T2, T3, T4
**步骤：**
1. 构造接收 Config，创建 HttpClient
2. 实现 chatStream()：构造 OpenAI Chat JSON → stream:true → SSE 逐行解析 → 回调
**验证：** mvn compile 通过

## T7：实现 ProviderFactory

**文件：** provider/ProviderFactory.java
**依赖：** T5, T6
**步骤：**
1. 根据 protocol 返回对应 Provider 实例
**验证：** mvn compile 通过

## T8：实现 ConversationMgr

**文件：** conversation/ConversationMgr.java
**依赖：** T2
**步骤：**
1. 内部 List<Message>，addUser/Assistant 方法，getHistory 返回不可变副本
**验证：** mvn compile 通过

## T9：实现 TUI

**文件：** tui/Tui.java
**依赖：** T4, T8
**步骤：**
1. JLine Terminal + LineReader 创建
2. 主循环：提示符 → readLine → 命令/对话分发
3. StreamHandler 实现 onToken → System.out.print
**验证：** mvn compile 通过

## T10：实现 Main 入口

**文件：** Main.java
**依赖：** T3, T7, T9
**步骤：**
1. ConfigLoader.load → ProviderFactory.create → Tui.start
**验证：** mvn compile 通过

## T11：配置模板文件

**文件：** easycode.yaml.example
**依赖：** 无
**步骤：**
1. 创建带注释的 YAML 模板
**验证：** 文件存在

## T12：旧代码清理

**文件：** src/main/java/com/easycode/agent/
**依赖：** 无
**步骤：**
1. 删除 agent 包下全部文件
**验证：** mvn compile 通过

## T13：ConfigLoader 单元测试

**文件：** src/test/.../config/ConfigLoaderTest.java
**依赖：** T3
**步骤：**
1. 正常加载、文件缺失、字段缺失、非法 protocol 四个用例
**验证：** mvn test -Dtest=ConfigLoaderTest 全部通过

## T14：ConversationMgr 单元测试

**文件：** src/test/.../conversation/ConversationMgrTest.java
**依赖：** T8
**步骤：**
1. 空列表、单条追加、多条顺序、不可变返回
**验证：** mvn test -Dtest=ConversationMgrTest 全部通过

## T15：ProviderFactory 单元测试

**文件：** src/test/.../provider/ProviderFactoryTest.java
**依赖：** T7
**步骤：**
1. anthropic → AnthropicProvider、openai → OpenAIProvider、非法 → 异常
**验证：** mvn test -Dtest=ProviderFactoryTest 全部通过

## 执行顺序

```
T1 → T2 → T4 → T8 → T9 → T10
      ↘
T3 → T5 → T6 → T7
                  ↘
              T13  T14  T15 (可并行)
T11, T12 (可随时做)
```


