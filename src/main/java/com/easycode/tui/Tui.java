package com.easycode.tui;

import com.easycode.agent.AgentEvent;
import com.easycode.agent.AgentLoop;
import com.easycode.config.Config;
import com.easycode.conversation.ConversationMgr;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import java.io.IOException;

/** JLine 交互界面，含 AgentLoop 事件消费 */
public final class Tui {

    private static final String PROMPT = "> ";
    private static final String dim = "\033[2m";
    private static final String reset = "\033[0m";
    private static final String red = "\033[31m";
    private static final String yellow = "\033[33m";
    private static final String cyan = "\033[36m";
    private static final String green = "\033[32m";
    private static final String bold = "\033[1m";
    private static final String boldOff = "\033[22m";
    private static final String magenta = "\033[35m";
    private static final String blue = "\033[34m";
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final AgentLoop agentLoop;
    private final ToolRegistry tools;

    private int totalInputTokens;
    private int totalOutputTokens;
    private int currentRound;
    private volatile boolean spinnerRunning;
    private Thread spinnerThread;

    // Markdown 流式渲染状态
    private boolean mdBold;
    private boolean mdCode;
    private boolean mdPendingStar;
    private boolean mdAtLineStart = true;
    private int mdHeadingHashes;

    public Tui(AgentLoop agentLoop, ToolRegistry tools, ConversationMgr conversation, Config config) {
        this.agentLoop = agentLoop;
        this.tools = tools;
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
            if ("/plan".equals(line)) {
                agentLoop.setPlanMode(true);
                System.out.println(green + "  已进入 Plan Mode（仅只读工具）" + reset);
                System.out.println(dim + "  模型将产出计划，不会改动文件。输入 /do 开始执行。" + reset);
                continue;
            }
            if ("/do".equals(line)) {
                agentLoop.setPlanMode(false);
                System.out.println(green + "  已进入 Do Mode（全工具可用）" + reset);
                System.out.println(dim + "  模型将按上文计划执行。" + reset);
                continue;
            }

            startStreamingChat(line);
        }
        terminal.close();
    }

    private void printWelcome() {
        System.out.println();
        System.out.println(cyan + "   ███████╗ █████╗ ███████╗██╗   ██╗" + reset);
        System.out.println(cyan + "   ██╔════╝██╔══██╗██╔════╝╚██╗ ██╔╝" + reset);
        System.out.println(blue + "   █████╗  ███████║███████╗ ╚████╔╝ " + reset);
        System.out.println(blue + "   ██╔══╝  ██╔══██║╚════██║  ╚██╔╝  " + reset);
        System.out.println(magenta + "   ███████╗██║  ██║███████║   ██║   " + bold + " CODE" + reset);
        System.out.println(magenta + "   ╚══════╝╚═╝  ╚═╝╚══════╝   ╚═╝   " + reset);
        System.out.println();
        System.out.println("   " + dim + "⚡ Terminal AI Assistant — 智能编程助手" + reset);
        System.out.println();
        System.out.println("   " + cyan + "─────────────────────────────────────────" + reset);
        System.out.println("   " + dim + "💬 输入问题   " + reset + "·" + dim + "  /plan 计划   " + reset + "·" + dim + "  /do 执行" + reset);
        System.out.println("   " + dim + "❓ /help 帮助 " + reset + "·" + dim + "  /exit 退出" + reset);
        System.out.println("   " + cyan + "─────────────────────────────────────────" + reset);
        System.out.println();
    }

    private void printHelp() {
        System.out.println();
        System.out.println(bold + "命令：" + reset + "  /exit 退出  /help 帮助  /plan 计划模式  /do 执行模式  Ctrl+D 退出");
        System.out.println(bold + "工具：" + reset);
        for (var entry : tools.byCategory().entrySet()) {
            System.out.println("  " + entry.getKey().name().toLowerCase() + ": " + String.join(", ", entry.getValue()));
        }
        System.out.println();
    }

    // ========== Agent Loop 事件处理 ==========

    private long startTime;
    private boolean lastEventWasTool;
    private StringBuilder currentPreamble;

    private void startStreamingChat(String userMessage) {
        totalInputTokens = 0;
        totalOutputTokens = 0;
        currentRound = 0;
        startTime = System.currentTimeMillis();
        lastEventWasTool = false;
        currentPreamble = new StringBuilder();
        mdBold = false;
        mdCode = false;
        mdPendingStar = false;
        mdAtLineStart = true;
        mdHeadingHashes = 0;

        startSpinner("思考中");
        agentLoop.run(userMessage, this::handleEvent);
        stopSpinner();
    }

    private void handleEvent(AgentEvent event) {
        if (event instanceof AgentEvent.TextDelta td) {
            if (spinnerRunning) {
                stopSpinner();
                System.out.println();
            }
            if (lastEventWasTool) {
                System.out.println();
            }
            renderMarkdownChunk(td.text());
            currentPreamble.append(td.text());
            lastEventWasTool = false;
        } else if (event instanceof AgentEvent.ToolCallStart tcs) {
            stopSpinner();
            lastEventWasTool = true;
        } else if (event instanceof AgentEvent.ToolCallEnd tce) {
            renderToolCallEnd(tce.result(), tce.toolName());
            lastEventWasTool = true;
        } else if (event instanceof AgentEvent.TokenUsage tu) {
            totalInputTokens = tu.totalInput();
            totalOutputTokens = tu.totalOutput();
        } else if (event instanceof AgentEvent.IterationProgress ip) {
            currentRound = ip.round();
        } else if (event instanceof AgentEvent.RoundComplete rc) {
            if (currentPreamble.length() > 0) {
                currentPreamble.setLength(0);
            }
        } else if (event instanceof AgentEvent.Error err) {
            stopSpinner();
            System.err.println(red + "[错误] " + err.message() + reset);
        } else if (event instanceof AgentEvent.AgentFinished af) {
            // 关闭未闭合的 markdown 状态
            if (mdBold) { System.out.print(boldOff); mdBold = false; }
            if (mdCode) { System.out.print(reset); mdCode = false; }
            double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
            System.out.print(" " + dim + "(" + String.format("%.1f", elapsed) + "s" + reset);
            if (af.totalInputTokens() > 0 || af.totalOutputTokens() > 0) {
                System.out.print(dim + " · " + af.totalInputTokens() + "+"
                        + af.totalOutputTokens() + " tokens" + reset);
            }
            System.out.println(")");
        }
    }

    // ========== Markdown 流式渲染 ==========

    /** 逐字符处理 markdown，转换为 ANSI 转义序列后输出 */
    private void renderMarkdownChunk(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);

            // 行首 # 检测
            if (mdAtLineStart && c == '#') {
                mdHeadingHashes++;
                continue;
            }
            if (mdHeadingHashes > 0 && c == ' ') {
                // 确认是标题，输出 bold 开始
                System.out.print(bold);
                mdBold = true;
                mdHeadingHashes = 0;
                mdAtLineStart = false;
                continue;
            }
            if (mdHeadingHashes > 0 && c != '#' && c != ' ') {
                // 不是标题，回退 # 号
                for (int j = 0; j < mdHeadingHashes; j++) System.out.print('#');
                mdHeadingHashes = 0;
            }

            mdAtLineStart = false;

            // ** 粗体
            if (c == '*') {
                if (mdPendingStar) {
                    mdPendingStar = false;
                    if (mdBold) {
                        System.out.print(boldOff);
                        mdBold = false;
                    } else {
                        System.out.print(bold);
                        mdBold = true;
                    }
                } else {
                    mdPendingStar = true;
                }
                continue;
            }
            if (mdPendingStar) {
                System.out.print('*');
                mdPendingStar = false;
            }

            // ` 行内代码
            if (c == '`') {
                if (mdCode) {
                    System.out.print(reset);
                    mdCode = false;
                } else {
                    System.out.print(yellow);
                    mdCode = true;
                }
                continue;
            }

            System.out.print(c);

            if (c == '\n') {
                mdAtLineStart = true;
                mdHeadingHashes = 0;
            }
        }
        System.out.flush();
    }

    // ========== 工具执行状态美化 ==========

    /** 工具执行进度指示：彩色图标 + 工具名 + 耗时 */
    private void renderToolCallEnd(ToolResult result, String toolName) {
        String catIcon = categoryIcon(toolName);
        String catColor = categoryColor(toolName);
        String status = result.success() ? green + "✓" : red + "✗";
        String toolColor = result.success() ? catColor : red;
        System.out.println("  " + status + " " + dim + catIcon + " " + reset
                + toolColor + toolName + reset
                + dim + "  " + result.durationMs() + "ms" + reset);
    }

    private String categoryIcon(String toolName) {
        return switch (toolName) {
            case "read_file", "write_file", "edit_file" -> "📄";
            case "exec_command" -> "⚡";
            case "find_files", "grep_code" -> "🔍";
            default -> "•";
        };
    }

    private String categoryColor(String toolName) {
        return switch (toolName) {
            case "read_file", "find_files", "grep_code" -> blue;
            case "write_file", "edit_file" -> yellow;
            case "exec_command" -> magenta;
            default -> reset;
        };
    }

    // ========== 转圈动画 ==========

    private void startSpinner(String label) {
        spinnerRunning = true;
        System.out.print(dim + "  " + label + "  " + reset);
        System.out.flush();
        Thread t = new Thread(() -> {
            int i = 0;
            try {
                while (spinnerRunning && !Thread.currentThread().isInterrupted()) {
                    String status = currentRound > 0
                        ? "  第" + currentRound + "/10轮 " + label + " " + SPINNER[i % SPINNER.length]
                        : "  " + label + " " + SPINNER[i % SPINNER.length];
                    System.out.print("\r" + dim + status + reset);
                    System.out.flush();
                    i++;
                    Thread.sleep(120);
                }
            } catch (InterruptedException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        this.spinnerThread = t;
    }

    private void stopSpinner() {
        spinnerRunning = false;
        if (spinnerThread != null) {
            try {
                spinnerThread.join(200);
            } catch (InterruptedException ignored) {}
            spinnerThread = null;
        }
        System.out.print("\r\033[K");
        System.out.flush();
    }
}
