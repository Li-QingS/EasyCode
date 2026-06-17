package com.easycode.tui;

import com.easycode.config.Config;
import com.easycode.conversation.ConversationMgr;
import com.easycode.conversation.MessageBlock;
import com.easycode.conversation.MessageRecord;
import com.easycode.conversation.Role;
import com.easycode.provider.LlmProvider;
import com.easycode.provider.StreamHandler;
import com.easycode.provider.ToolCall;
import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import java.io.IOException;
import java.util.List;

/** JLine 交互界面，含工具调用执行 */
public final class Tui {

    private static final String PROMPT = "> ";
    private static final String dim = "\033[2m";
    private static final String reset = "\033[0m";
    private static final String red = "\033[31m";
    private static final String yellow = "\033[33m";
    private static final String cyan = "\033[36m";
    private static final String green = "\033[32m";
    private static final String bold = "\033[1m";
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final LlmProvider provider;
    private final ConversationMgr conversation;
    private final ToolRegistry tools;
    private final Config config;

    public Tui(LlmProvider provider, ToolRegistry tools, Config config) {
        this.provider = provider;
        this.conversation = new ConversationMgr();
        this.tools = tools;
        this.config = config;
    }

    public void start() throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

        if ("dumb".equals(terminal.getType())) {
            System.err.println("⚠ 未检测到真实终端（TTY），请在以下环境中运行：");
            System.err.println("  Windows Terminal / PowerShell / tmux / 原生终端");
            terminal.close();
            return;
        }

        printWelcome();

        while (true) {
            String line = reader.readLine(PROMPT);
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            if ("/exit".equals(line)) break;
            if ("/help".equals(line)) { printHelp(); continue; }

            conversation.addUserMessage(line);
            conversation.trimToWindow(config.contextWindow());
            startStreamingChat();
        }
        terminal.close();
    }

    private void printWelcome() {
        System.out.println();
        System.out.println(cyan + bold + "  ╔══════════════════════════╗" + reset);
        System.out.println(cyan + bold + "  ║" + reset + "      EasyCode v1.0.0      " + cyan + bold + "║" + reset);
        System.out.println(cyan + bold + "  ║" + reset + "  Terminal AI Assistant    " + cyan + bold + "║" + reset);
        System.out.println(cyan + bold + "  ╚══════════════════════════╝" + reset);
        System.out.println();
        System.out.println("  " + dim + "输入问题 · /help 查看命令 · /exit 退出" + reset);
        System.out.println();
    }

    private void printHelp() {
        System.out.println();
        System.out.println(bold + "命令：" + reset + "  /exit 退出  /help 帮助  Ctrl+D 退出");
        System.out.println(bold + "工具：" + reset + "  " + String.join(" ", tools.toToolsJson().stream().map(n -> n.get("name").asText()).toList()));
        System.out.println();
    }

    // ========== 对话流程 ==========

    private boolean needFollowUp;

    private void startStreamingChat() {
        needFollowUp = false;
        doStreamingChat(conversation.getHistory());
        if (needFollowUp) {
            needFollowUp = false;
            System.out.println(dim + "[二次请求] 发起第二次 chatStream..." + reset);
            doStreamingChat(conversation.getHistory());
        }
    }

    private boolean spinnerStopped;

    private void doStreamingChat(List<MessageRecord> history) {
        List<JsonNode> toolList = tools.toToolsJson();
        long startTime = System.currentTimeMillis();
        spinnerStopped = false;
        Thread spinner = startSpinner("思考中");
        final boolean[] firstToken = {true};
        StringBuilder fullResponse = new StringBuilder();

        provider.chatStream(history, toolList, new MarkdownRenderer(new StreamHandler() {
            public void onToken(String token) {
                if (firstToken[0]) { stopSpinner(spinner); firstToken[0] = false; }
                System.out.print(token);
                System.out.flush();
                fullResponse.append(token);
            }
            public void onToolCall(ToolCall call) {
                stopSpinner(spinner);
                firstToken[0] = false;
                if (needFollowUp) {
                    // 已在二次调用中又触发工具 → 跳过，避免无限递归
                    return;
                }
                handleToolCall(call);
            }
            public void onUsage(int in, int out) {
                // token 用量暂存（后续可展示）
            }
            public void onComplete() {
                if (!spinnerStopped && !needFollowUp) {
                    stopSpinner(spinner);
                }
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                if (fullResponse.isEmpty() && needFollowUp) {
                    // 工具调用流：不打印空计时
                } else {
                    System.out.printf(" " + dim + "(%.1fs)" + reset + "%n", elapsed);
                }
                if (!fullResponse.isEmpty() && !needFollowUp) {
                    conversation.addAssistantMessage(fullResponse.toString());
                }
            }
            public void onError(Exception e) {
                stopSpinner(spinner);
                System.err.println(red + "[错误] " + e.getMessage() + reset);
            }
        }));
    }

    // ========== 工具执行 ==========

    private void handleToolCall(ToolCall call) {
        Tool tool = tools.get(call.name());

        // 需要用户确认的工具
        if (tool.requiresApproval()) {
            System.out.println(yellow + "⚠ " + call.name() + " (读写操作)" + reset);
            System.out.print(yellow + "   是否执行？[y/n] " + reset);
            System.out.flush();
            try {
                int ch = System.in.read();
                // 吞掉换行符
                while (System.in.available() > 0) System.in.read();
                if (ch != 'y' && ch != 'Y') {
                    System.out.println(red + "   已取消" + reset);
                    // 灌回拒绝信息
                    conversation.addMessage(new MessageRecord(Role.ASSISTANT, "",
                        List.of(new MessageBlock.ToolUseBlock(call.id(), call.name(), call.input()))));
                    conversation.addMessage(new MessageRecord(Role.USER, "",
                        List.of(new MessageBlock.ToolResultBlock(call.id(), "用户拒绝了工具调用", true))));
                    needFollowUp = true;
                    return;
                }
                System.out.println(green + "   已允许" + reset);
            } catch (java.io.IOException e) {
                System.out.println(red + "   读取输入失败，跳过" + reset);
                return;
            }
        }

        System.out.println(dim + "[工具] 开始执行..." + reset);
        System.out.println();
        System.out.print(yellow + "🔧 " + call.name() + reset + "  ");
        System.out.flush();
        Thread spin = startSpinner("执行中");

        ToolResult result = tool.execute(call.input());

        stopSpinner(spin);
        String status = result.success() ? green + "✅" : red + "❌";
        System.out.println(status + " " + call.name() + reset
                + dim + " (" + result.durationMs() + "ms)" + reset);

        // 结果折叠摘要
        String preview = result.content();
        if (preview.length() > 500) preview = preview.substring(0, 500) + "\n... (截断)";
        if ("edit_file".equals(call.name()) || "grep_code".equals(call.name()) || "read_file".equals(call.name())) {
            // 多行输出工具：保留换行，edit 高亮 diff
            String colored = "edit_file".equals(call.name())
                ? preview.replace("- ", red + "- " + dim).replace("+ ", green + "+ " + dim)
                : preview;
            System.out.println(dim + colored + reset);
        } else {
            System.out.println(dim + preview.replace("\n", "\\n") + reset);
        }
        System.out.println();

        // 灌回对话历史
        MessageRecord toolUseMsg = new MessageRecord(Role.ASSISTANT, "",
                List.of(new MessageBlock.ToolUseBlock(call.id(), call.name(), call.input())));
        MessageRecord toolResultMsg = new MessageRecord(Role.USER, "",
                List.of(new MessageBlock.ToolResultBlock(call.id(), result.content(), !result.success())));
        conversation.addMessage(toolUseMsg);
        conversation.addMessage(toolResultMsg);

        // 标记需要二次调用，不直接嵌套启动
        needFollowUp = true;
    }

    // ========== 转圈动画 ==========

    private Thread startSpinner(String label) {
        System.out.print(dim + "⏳ " + label + "  " + reset);
        System.out.flush();
        Thread t = new Thread(() -> {
            int i = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    System.out.print("\r" + dim + "⏳ " + label + " " + SPINNER[i % SPINNER.length] + reset);
                    System.out.flush();
                    i++;
                    Thread.sleep(120);
                }
            } catch (InterruptedException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void stopSpinner(Thread spinner) {
        spinner.interrupt();
        spinnerStopped = true;
        System.out.print("\r\033[K");
        System.out.flush();
    }
}
