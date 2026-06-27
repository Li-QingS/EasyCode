package com.easycode.tui;

import com.easycode.agent.AgentEvent;
import com.easycode.agent.AgentLoop;
import com.easycode.command.CommandDispatcher;
import com.easycode.command.CommandRegistry;
import com.easycode.command.CommandResult;
import com.easycode.command.UiController;
import com.easycode.config.Config;
import com.easycode.context.CompressEvent;
import com.easycode.conversation.ConversationMgr;
import com.easycode.permission.PermissionMode;
import com.easycode.permission.PermissionPipeline;
import com.easycode.session.SessionContext;
import com.easycode.session.SessionResumer;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Completer;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class Tui implements UiController {

    private static final String dim="\033[2m",reset="\033[0m",red="\033[31m";
    private static final String yellow="\033[33m",cyan="\033[36m",green="\033[32m",bold="\033[1m";
    private static final String magenta="\033[35m",blue="\033[34m",boldOff="\033[22m";
    private static final String[] SPINNER={"\u280b","\u2819","\u2839","\u2838","\u283c","\u2834","\u2826","\u2827","\u2807","\u280f"};

    /** 匹配 ANSI 转义序列，用于计算终端列宽 */
    private static final Pattern ANSI = Pattern.compile("\033\\[[0-9;]*[a-zA-Z]");

    private final AgentLoop agentLoop; private final ToolRegistry tools;
    private final ConversationMgr conversation; private final Config config;
    private PermissionPipeline permPipeline; private PermissionMode permMode=PermissionMode.DEFAULT;
    private int totalInputTokens,totalOutputTokens,currentRound;
    private int lastRoundInputTokens,lastRoundOutputTokens;
    private volatile boolean spinnerRunning; private Thread spinnerThread;
    private long startTime; private boolean lastEventWasTool,mdBold,mdCode,mdPendingStar,mdAtLineStart=true,agentPrefixShown;
    private int mdHeadingHashes; private StringBuilder currentPreamble;
    private final CommandDispatcher dispatcher; private final long appStartTime;
    private final String sessionId;

    public Tui(AgentLoop al, ToolRegistry tr, ConversationMgr cm, Config cf, PermissionPipeline pp,
               CommandDispatcher dispatcher, String sessionId) {
        agentLoop=al; tools=tr; conversation=cm; config=cf; permPipeline=pp; permMode=pp.startMode();
        this.dispatcher = dispatcher; this.appStartTime = System.currentTimeMillis();
        this.sessionId = sessionId;
    }

    // ======================== 主循环 ========================

    public void start() throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal)
            .completer(new CommandCompleter())
            .build();
        if ("dumb".equals(terminal.getType())) { System.err.println("no TTY"); terminal.close(); return; }
        printWelcome();
        long startTimeMs = appStartTime;
        while (true) {
            String line;
            try { line = reader.readLine(modePrompt()); }
            catch (UserInterruptException e) { System.out.println(dim+"  [已取消]"+reset); continue; }
            catch (EndOfFileException e) { break; }
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            var result = dispatcher.dispatch(line);
            if (result.isEmpty()) {
                conversation.addUserMessage(line);
                conversation.trimToWindow(config.contextWindow());
                startStreamingChat(line);
                continue;
            }
            CommandResult cr = result.get();
            if (cr instanceof CommandResult.Exit) {
                terminal.close(); return;
            } else if (cr instanceof CommandResult.Ok) {
                // no output needed
            } else if (cr instanceof CommandResult.Message msg) {
                if (!msg.text().isEmpty()) System.out.println(msg.text());
            } else if (cr instanceof CommandResult.Prompt prompt) {
                conversation.addUserMessage(prompt.promptText());
                conversation.trimToWindow(config.contextWindow());
                startStreamingChat(prompt.promptText());
            } else if (cr instanceof CommandResult.NotFound nf) {
                System.out.println(dim+"  未知命令: /"+nf.commandName()+"，输入 /help 查看可用命令"+reset);
            } else if (cr instanceof CommandResult.Error err) {
                System.out.println(red+"  命令错误: "+err.message()+reset);
            }
        }
        terminal.close();
    }

    // ======================== 提示符 ========================

    private String modePrompt() {
        String m = switch (permMode) {
            case DEFAULT -> "DEFAULT"; case ACCEPT_EDITS -> "EDIT";
            case PLAN -> "PLAN"; case BYPASS_PERMISSIONS -> "BYPASS";
        };
        return dim+"["+m+"]"+reset+" > ";
    }

    // ======================== 欢迎与帮助 ========================

    private void printWelcome() {
        String C="\033[1;36m",W="\033[0m",D="\033[2m",G="\033[32m",B="\033[1m";
        String A="\033[38;5;51m",P="\033[38;5;213m",S="\033[38;5;228m";

        // 内容行（纯内容，不含左右边框 ║）
        String[] inner = {
            "  "+A+"███████╗"+W+" "+P+"█████╗"+W+" "+S+"███████╗"+W+" "+A+"██╗   ██╗"+W,
            "  "+A+"██╔════╝"+W+" "+P+"██╔══██╗"+W+S+"██╔════╝"+W+" "+A+"╚██╗ ██╔╝"+W,
            "  "+A+"█████╗  "+W+" "+P+"███████║"+W+S+"███████╗"+W+" "+A+" ╚████╔╝"+W,
            "  "+A+"██╔══╝  "+W+" "+P+"██╔══██║"+W+S+"╚════██║"+W+" "+A+"  ╚██╔╝"+W,
            "  "+A+"███████╗"+W+" "+P+"██║  ██║"+W+S+"███████║"+W+" "+A+"   ██║"+W,
            "  "+A+"╚══════╝"+W+" "+P+"╚═╝  ╚═╝"+W+S+"╚══════╝"+W+" "+A+"   ╚═╝"+W,
            "",
            "     "+B+"AI Coding Agent"+W+D+"  v1.0"+W,
            "     "+D+"读/写/改文件 · 执行命令 · 搜索代码"+W,
            "     "+D+"Java 17 · JLine · Anthropic / OpenAI"+W,
            "",
        };

        String cmd1 = "  "+G+"/permission"+W+D+" 权限"+W+"  │  "
                    + G+"/compact"+W+D+" 压缩"+W+"  │  "
                    + G+"/session"+W+D+" 会话"+W;
        String cmd2 = "  "+G+"/help"+W+D+" 帮助"+W+"  │  "
                    + G+"/exit"+W+D+" 退出"+W+"  │  "
                    + G+"/about"+W+D+" 关于"+W;
        String tip  = "  "+D+"直接输入问题开始对话，/ 开头执行内置命令"+W;

        // 计算最大内容宽度——不含左右边框
        int w = 42;
        for (String s : inner) w = Math.max(w, colWidth(s));
        w = Math.max(w, colWidth(cmd1));
        w = Math.max(w, colWidth(cmd2));
        w = Math.max(w, colWidth(tip));

        String h  = "═".repeat(w);   // 双线（顶/底）
        String hs = "─".repeat(w);   // 单线（分隔）

        System.out.println();
        System.out.println(C+"  ╔"+h+"╗"+W);
        for (String s : inner) System.out.println(C+"  ║"+W+" "+padTo(s,w)+" "+C+"║"+W);
        System.out.println(C+"  ╟"+D+hs+C+"╢"+W);
        System.out.println(C+"  ║"+W+" "+padTo(cmd1,w)+" "+C+"║"+W);
        System.out.println(C+"  ║"+W+" "+padTo(cmd2,w)+" "+C+"║"+W);
        System.out.println(C+"  ║"+W+" "+padTo(tip, w)+" "+C+"║"+W);
        System.out.println(C+"  ╚"+h+"╝"+W);
        System.out.println();
    }

    /** 去除 ANSI 码后的终端列宽（中文等宽字符计 2 列） */
    private static int colWidth(String s) {
        String clean = ANSI.matcher(s).replaceAll("");
        int w = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < clean.length()
                    && Character.isLowSurrogate(clean.charAt(i + 1))) {
                w += 2; i++;
            } else if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) {
                w += 2;
            } else {
                w += 1;
            }
        }
        return w;
    }

    /** 补齐空格到目标列宽 */
    private static String padTo(String s, int targetCols) {
        int cur = colWidth(s);
        if (cur >= targetCols) return s;
        return s + " ".repeat(targetCols - cur);
    }
    // ======================== 对话流程 ========================

    private void startStreamingChat(String um) {
        totalInputTokens = totalOutputTokens = currentRound = 0;
        startTime = System.currentTimeMillis();
        lastEventWasTool = false;
        currentPreamble = new StringBuilder();
        agentPrefixShown = false;
        mdBold = mdCode = mdPendingStar = false;
        mdAtLineStart = true;
        mdHeadingHashes = 0;
        startSpinner("思考中");
        agentLoop.run(um, this::handleEvent);
        stopSpinner();
    }

    private void handleEvent(AgentEvent e) {
        if (e instanceof AgentEvent.TextDelta td) {
            if (spinnerRunning) { stopSpinner(); System.out.println(); }
            if (lastEventWasTool) System.out.println();
            if (!agentPrefixShown) {
                agentPrefixShown = true;
                System.out.print(green + bold + "  \u25b8 " + reset);
            }
            renderMarkdown(td.text());
            currentPreamble.append(td.text());
            lastEventWasTool = false;
        } else if (e instanceof AgentEvent.ToolCallStart tcs) {
            stopSpinner();
            lastEventWasTool = true;
        } else if (e instanceof AgentEvent.ToolCallEnd tce) {
            renderToolCall(tce.result(), tce.toolName());
            lastEventWasTool = true;
        } else if (e instanceof AgentEvent.TokenUsage tu) {
            totalInputTokens = tu.totalInput();
            totalOutputTokens = tu.totalOutput();
            lastRoundInputTokens = tu.roundInput();
            lastRoundOutputTokens = tu.roundOutput();
        } else if (e instanceof AgentEvent.IterationProgress ip) {
            currentRound = ip.round();
        } else if (e instanceof AgentEvent.RoundComplete rc) {
            if (currentPreamble.length() > 0) currentPreamble.setLength(0);
        } else if (e instanceof AgentEvent.Error err) {
            stopSpinner();
            System.err.println(red+"[错误] "+err.message()+reset);
        } else if (e instanceof AgentEvent.PermissionAsk ask) {
            handlePermissionAsk(ask);
        } else if (e instanceof AgentEvent.ContextCompress cc) {
            stopSpinner();
            var evt = cc.event();
            String label = switch (evt.reason()) {
                case AUTO -> "自动压缩";
                case MANUAL -> "手动压缩";
                case EMERGENCY -> "紧急压缩";
            };
            System.out.println((evt.success()?green:red)+bold+"  \u25c8 "+label+": "+evt.toDisplay()+reset);
        } else if (e instanceof AgentEvent.AgentFinished af) {
            if (mdBold) { System.out.print(boldOff); mdBold = false; }
            if (mdCode) { System.out.print(reset); mdCode = false; }
            double el = (System.currentTimeMillis() - startTime) / 1000.0;
            System.out.print(" "+dim+"("+String.format("%.1f", el)+"s"+reset);
            if (lastRoundInputTokens > 0 || lastRoundOutputTokens > 0)
                System.out.print(dim+" \u00b7 本轮入:"+lastRoundInputTokens+" 出:"+lastRoundOutputTokens+reset);
            if (af.totalInputTokens() > 0 || af.totalOutputTokens() > 0)
                System.out.print(dim+" \u00b7 累计入:"+af.totalInputTokens()+" 出:"+af.totalOutputTokens()+reset);
            int ctxEst = conversation.estimateTokens();
            int ctxWin = config.contextWindow();
            System.out.print(dim+" \u00b7 上下文:"+ctxEst+"/"+ctxWin+reset);
            System.out.println(")");
        }
    }

    // ======================== 权限确认 ========================

    private void handlePermissionAsk(AgentEvent.PermissionAsk ask) {
        String[] opts = {"允许本次", "永久", "拒绝"};
        int sel = 0;
        System.out.println(dim+"  \u256d\u2500 权限确认 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"+reset);
        System.out.println(dim+"  \u2502 工具: "+reset+bold+ask.toolName()+reset);
        System.out.println(dim+"  \u2502 参数: "+reset+ask.preview());
        System.out.println(dim+"  \u2502 原因: "+reset+ask.reason());
        System.out.println(dim+"  \u2570\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"+reset);
        try {
            while (true) {
                for (int i = 0; i < opts.length; i++) {
                    System.out.print("\r\033[K  "+(i==sel ? bold+"\u25b6 "+opts[i]+reset : dim+"  "+opts[i]+reset)+"\n");
                }
                System.out.print("\r\033[K  "+dim+"[\u2191\u2193/Enter/1-3]"+reset+"\n");
                System.out.flush();
                try { Thread.sleep(60); } catch (InterruptedException ie) {}
                int ch = System.in.read();
                if (ch == '\n' || ch == '\r') break;
                else if (ch == '1') { sel = 0; break; }
                else if (ch == '2') { sel = 1; break; }
                else if (ch == '3') { sel = 2; break; }
                else if (ch == 27) {
                    if (System.in.available() > 0) {
                        int c2 = System.in.read();
                        if (c2 == 91) {
                            int c3 = System.in.read();
                            if (c3 == 65) sel = (sel + 2) % 3;
                            else if (c3 == 66) sel = (sel + 1) % 3;
                        }
                    } else { sel = 2; break; }
                }
                System.out.print("\033[4A"); System.out.flush();
            }
            while (System.in.available() > 0) System.in.read();
            System.out.print("\033[1A\r\033[K");
            ask.future().complete(switch (sel) { case 0 -> "allow"; case 1 -> "permanent"; default -> "deny"; });
        } catch (Exception ex) { ask.future().complete("deny"); }
    }

    // ======================== 渲染 ========================

    private void renderMarkdown(String ch) {
        for (int i = 0; i < ch.length(); i++) {
            char c = ch.charAt(i);
            if (mdAtLineStart && c == '#') { mdHeadingHashes++; continue; }
            if (mdHeadingHashes > 0 && c == ' ') { System.out.print(bold); mdBold = true; mdHeadingHashes = 0; mdAtLineStart = false; continue; }
            if (mdHeadingHashes > 0 && c != '#' && c != ' ') { for (int j = 0; j < mdHeadingHashes; j++) System.out.print('#'); mdHeadingHashes = 0; }
            mdAtLineStart = false;
            if (c == '*') {
                if (mdPendingStar) { mdPendingStar = false; System.out.print(mdBold ? boldOff : bold); mdBold = !mdBold; }
                else { mdPendingStar = true; }
                continue;
            }
            if (mdPendingStar) { System.out.print('*'); mdPendingStar = false; }
            if (c == '`') { System.out.print(mdCode ? reset : yellow); mdCode = !mdCode; continue; }
            System.out.print(c);
            if (c == '\n') { mdAtLineStart = true; mdHeadingHashes = 0; }
        }
        System.out.flush();
    }

    private void renderToolCall(ToolResult r, String n) {
        String ic = switch (n) {
            case "read_file", "write_file", "edit_file" -> "\uD83D\uDCC4";
            case "exec_command" -> "\u26A1";
            default -> "\uD83D\uDD0D";
        };
        String co = switch (n) {
            case "read_file", "find_files", "grep_code" -> blue;
            case "write_file", "edit_file" -> yellow;
            case "exec_command" -> magenta;
            default -> reset;
        };
        System.out.println("  "+(r.success()?green+"\u2713":red+"\u2717")+" "+dim+ic+reset+" "+co+n+reset+dim+"  "+r.durationMs()+"ms"+reset);
    }

    // ======================== Spinner ========================

    private void startSpinner(String l) {
        spinnerRunning = true;
        System.out.print(dim+"  "+l+"  "+reset);
        System.out.flush();
        Thread t = new Thread(() -> {
            int i = 0;
            try {
                while (spinnerRunning && !Thread.currentThread().isInterrupted()) {
                    String s = currentRound > 0
                        ? "  第"+currentRound+"/10轮 "+l+" "+SPINNER[i%SPINNER.length]
                        : "  "+l+" "+SPINNER[i%SPINNER.length];
                    System.out.print("\r"+dim+s+reset);
                    System.out.flush();
                    i++;
                    Thread.sleep(120);
                }
            } catch (InterruptedException ie) {}
        });
        t.setDaemon(true);
        t.start();
        this.spinnerThread = t;
    }

    private void stopSpinner() {
        spinnerRunning = false;
        if (spinnerThread != null) {
            try { spinnerThread.join(200); } catch (InterruptedException ie) {}
        }
        spinnerThread = null;
        System.out.print("\r\033[K");
        System.out.flush();
    }

    // ======================== UiController 实现 ========================

    @Override public void showMessage(String message) {
        if (message != null && !message.isEmpty()) System.out.println(message);
    }

    @Override public void sendUserMessage(String message) {
        conversation.addUserMessage(message);
        conversation.trimToWindow(config.contextWindow());
        startStreamingChat(message);
    }

    @Override public void switchMode(PermissionMode mode) {
        permMode = mode;
        agentLoop.setPermMode(mode);
        agentLoop.setPlanMode(mode == PermissionMode.PLAN);
    }

    @Override public PermissionMode currentMode() { return permMode; }

    @Override public int[] tokenUsage() {
        return new int[]{
            lastRoundInputTokens, lastRoundOutputTokens,
            totalInputTokens, totalOutputTokens,
            conversation.estimateTokens(), config.contextWindow()
        };
    }

    @Override public void refreshStatus() { /* 提示符在每次 readLine 时重新计算 */ }

    @Override public void clearScreen() {
        System.out.print("\033[2J\033[H");
        System.out.flush();
    }

    @Override public String triggerCompact() {
        System.out.print(dim+"  正在压缩上下文..."+reset); System.out.flush();
        CompressEvent evt = agentLoop.forceCompact(tools.toToolsJson());
        System.out.print("\r\033[K");
        String result = (evt.success()?green:red)+bold+"  \u25c8 "+evt.toDisplay()+reset;
        System.out.println(result);
        return result;
    }

    @Override public String sessionId() { return sessionId; }

    @Override
    public void loadSessionHistory(java.util.List<com.easycode.conversation.MessageRecord> messages) {
        agentLoop.loadHistory(messages);
    }

    @Override public long startTimeMs() { return appStartTime; }

    // ======================== Tab 补全 ========================

    private class CommandCompleter implements Completer {
        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            String word = line.word();
            if (word == null || !word.startsWith("/")) return;
            var matches = dispatcher.complete(word);
            for (var m : matches) {
                candidates.add(new Candidate(m.name(), m.name(), null, m.description(), null, null, matches.size() == 1));
            }
        }
    }
}
