package com.easycode.tui;

import com.easycode.agent.AgentEvent;
import com.easycode.agent.AgentLoop;
import com.easycode.config.Config;
import com.easycode.conversation.ConversationMgr;
import com.easycode.permission.PermissionMode;
import com.easycode.permission.PermissionPipeline;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Completer;
import org.jline.reader.Candidate;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import java.io.IOException;

public final class Tui {

    private static final String dim="\033[2m",reset="\033[0m",red="\033[31m";
    private static final String yellow="\033[33m",cyan="\033[36m",green="\033[32m",bold="\033[1m";
    private static final String magenta="\033[35m",blue="\033[34m",boldOff="\033[22m";
    private static final String[] SPINNER={"⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"};

    private final AgentLoop agentLoop; private final ToolRegistry tools;
    private final ConversationMgr conversation; private final Config config;
    private PermissionPipeline permPipeline; private PermissionMode permMode=PermissionMode.DEFAULT;
    private int totalInputTokens,totalOutputTokens,currentRound;
    private volatile boolean spinnerRunning; private Thread spinnerThread;
    private long startTime; private boolean lastEventWasTool,mdBold,mdCode,mdPendingStar,mdAtLineStart=true;
    private int mdHeadingHashes; private StringBuilder currentPreamble;

    public Tui(AgentLoop al, ToolRegistry tr, ConversationMgr cm, Config cf, PermissionPipeline pp) {
        agentLoop=al;tools=tr;conversation=cm;config=cf;permPipeline=pp;permMode=pp.startMode();
    }

    public void start() throws IOException {
        Terminal terminal=TerminalBuilder.builder().system(true).build();
        LineReader reader=LineReaderBuilder.builder().terminal(terminal).build();
        if("dumb".equals(terminal.getType())){System.err.println("no TTY");terminal.close();return;}
        printWelcome();
        while(true){
            String line;
            try{line=reader.readLine(modePrompt());}
            catch(UserInterruptException e){System.out.println(dim+"  [已取消]"+reset);continue;}
            catch(EndOfFileException e){break;}
            if(line==null)break; line=line.trim();
            if(line.isEmpty())continue;
            if("/exit".equals(line))break;
            if("/help".equals(line)){printHelp();continue;}
            if("/perm".equals(line)){showPermMenu();continue;}
            conversation.addUserMessage(line); conversation.trimToWindow(config.contextWindow()); startStreamingChat(line);
        }
        terminal.close();
    }

    private void showPermMenu() {
        System.out.println("\n"+bold+cyan+"  ╭─ 权限模式 ─────────────────────────────"+reset);
        System.out.println(dim+"  │"+reset+" [1] "+bold+"DEFAULT"+reset+"        只读✓  写/命令🔔需确认");
        System.out.println(dim+"  │"+reset+" [2] "+bold+"ACCEPT_EDITS"+reset+"   只读✓  写✓  命令🔔需确认");
        System.out.println(dim+"  │"+reset+" [3] "+bold+"PLAN"+reset+"           仅只读工具,其余🔔需确认(计划模式)");
        System.out.println(dim+"  │"+reset+" [4] "+bold+"BYPASS"+reset+"        全部✓(黑名单除外)");
        System.out.println(dim+"  ╰─────────────────────────────────────────"+reset);
        System.out.print("  请选择 (1-4, Enter=取消): "); System.out.flush();
        try{
            int ch=System.in.read(); while(System.in.available()>0)System.in.read();
            PermissionMode newMode=switch(ch){case'1'->PermissionMode.DEFAULT;case'2'->PermissionMode.ACCEPT_EDITS;case'3'->PermissionMode.PLAN;case'4'->PermissionMode.BYPASS_PERMISSIONS;default->null;};
            if(newMode!=null){
                permMode=newMode; agentLoop.setPermMode(permMode);
                agentLoop.setPlanMode(newMode==PermissionMode.PLAN);
                System.out.println(green+"  已切换: "+permMode.name()+reset);
            }else{System.out.println(dim+"  已取消"+reset);}
        }catch(IOException e){System.out.println(dim+"  已取消"+reset);}
    }

    private String modePrompt(){
        String m=switch(permMode){case DEFAULT->"def";case ACCEPT_EDITS->"edit";case PLAN->"plan";case BYPASS_PERMISSIONS->"bypass";};
        return dim+"["+m+"]"+reset+" > ";
    }

    private void printWelcome() {
        String C="\033[1;36m",W="\033[0m",D="\033[2m",G="\033[32m",B="\033[1m",M="\033[35m",Y="\033[33m";
        String A="\033[38;5;51m",P="\033[38;5;213m",S="\033[38;5;228m";
        System.out.println();
        System.out.println(C+"  ╔══════════════════════════════════════════╗"+W);
        System.out.println(C+"  ║"+W+"  "+A+"███████╗"+W+" "+P+" █████╗"+W+" "+S+"███████╗"+W+" "+A+"██╗   ██╗"+W+"   "+C+"║"+W);
        System.out.println(C+"  ║"+W+"  "+A+"██╔════╝"+W+" "+P+"██╔══██╗"+W+""+S+"██╔════╝"+W+" "+A+"╚██╗ ██╔╝"+W+"   "+C+"║"+W);
        System.out.println(C+"  ║"+W+"  "+A+"█████╗  "+W+" "+P+"███████║"+W+""+S+"███████╗"+W+" "+A+" ╚████╔╝"+W+"    "+C+"║"+W);
        System.out.println(C+"  ║"+W+"  "+A+"██╔══╝  "+W+" "+P+"██╔══██║"+W+""+S+"╚════██║"+W+" "+A+"  ╚██╔╝"+W+"     "+C+"║"+W);
        System.out.println(C+"  ║"+W+"  "+A+"███████╗"+W+" "+P+"██║  ██║"+W+""+S+"███████║"+W+" "+A+"   ██║"+W+"      "+C+"║"+W);
        System.out.println(C+"  ║"+W+"  "+A+"╚══════╝"+W+" "+P+"╚═╝  ╚═╝"+W+""+S+"╚══════╝"+W+" "+A+"   ╚═╝"+W+"      "+C+"║"+W);
        System.out.println(C+"  ║"+W+"                                          "+C+"║"+W);
        System.out.println(Y+"  ║"+W+"    "+A+"██████╗"+W+"  "+P+" ██████╗"+W+" "+S+"██████╗"+W+"  "+A+"███████╗"+W+"   "+Y+"║"+W);
        System.out.println(Y+"  ║"+W+"   "+A+"██╔════╝"+W+" "+P+"██╔═══██╗"+W+""+S+"██╔══██╗"+W+" "+A+"██╔════╝"+W+"   "+Y+"║"+W);
        System.out.println(Y+"  ║"+W+"   "+A+"██║     "+W+" "+P+"██║   ██║"+W+""+S+"██║  ██║"+W+" "+A+"█████╗  "+W+"   "+Y+"║"+W);
        System.out.println(Y+"  ║"+W+"   "+A+"██║     "+W+" "+P+"██║   ██║"+W+""+S+"██║  ██║"+W+" "+A+"██╔══╝  "+W+"   "+Y+"║"+W);
        System.out.println(Y+"  ║"+W+"   "+A+"╚██████╗"+W+" "+P+"╚██████╔╝"+W+""+S+"██████╔╝"+W+" "+A+"███████╗"+W+"   "+Y+"║"+W);
        System.out.println(Y+"  ║"+W+"    "+A+"╚═════╝"+W+" "+P+" ╚═════╝"+W+" "+S+"╚═════╝"+W+" "+A+"╚══════╝"+W+"    "+Y+"║"+W);
        System.out.println(C+"  ║"+W+"                                          "+C+"║"+W);
        System.out.println(C+"  ╟"+W+D+"──────────────────────────────────────────"+W+C+"╢"+W);
        System.out.println(C+"  ║"+W+"     "+B+"AI Coding Agent"+W+D+"  v1.0"+W+"                     "+C+"║"+W);
        System.out.println(C+"  ║"+W+"     "+D+"读/写/改文件 · 执行命令 · 搜索代码"+W+"       "+C+"║"+W);
        System.out.println(C+"  ║"+W+"     "+D+"Java 17 · JLine · Anthropic / OpenAI"+W+"       "+C+"║"+W);
        System.out.println(C+"  ║"+W+"                                          "+C+"║"+W);
        System.out.println(C+"  ╟"+W+D+"──────────────────────────────────────────"+W+C+"╢"+W);
        System.out.println(C+"  ║"+W+"  "+G+"/perm"+W+D+"  权限模式"+W+"  │  "+G+"/help"+W+D+"  帮助"+W+"  │  "+G+"/exit"+W+D+"  退出"+W+"  "+C+"║"+W);
        System.out.println(C+"  ║"+W+"  "+D+"直接输入问题开始对话"+W+"                          "+C+"║"+W);
        System.out.println(C+"  ╚══════════════════════════════════════════╝"+W);
        System.out.println();
    }    private void printHelp() {
        String B="\033[1;36m",G="\033[32m",W="\033[0m",D="\033[2m";
        System.out.println();
        System.out.println(B+"  ╭─ 帮助 ─────────────────────────────────────"+W);
        System.out.println(B+"  │"+W);
        System.out.println(B+"  │"+W+bold+"  ⌨  命令"+W);
        System.out.println(B+"  │"+W+"    "+G+"/perm"+W+"       查看权限模式列表并切换");
        System.out.println(B+"  │"+W+"    "+G+"/help"+W+"       显示本帮助");
        System.out.println(B+"  │"+W+"    "+G+"/exit"+W+"       退出程序");
        System.out.println(B+"  │"+W+"    "+G+"Ctrl+C"+W+"      取消当前正在执行的请求");
        System.out.println(B+"  │"+W+"    "+G+"Ctrl+D"+W+"      退出程序(同 /exit)");
        System.out.println(B+"  │"+W);
        System.out.println(B+"  │"+W+bold+"  🛡  权限模式 (输入 /perm 切换)"+W);
        System.out.println(B+"  │"+W+"    "+bold+"DEFAULT"+W+"       只读工具直接执行");
        System.out.println(B+"  │"+W+"                 写文件和执行命令需要用户确认");
        System.out.println(B+"  │"+W+"    "+bold+"ACCEPT_EDITS"+W+"  只读和写文件直接执行");
        System.out.println(B+"  │"+W+"                 执行命令需要用户确认");
        System.out.println(B+"  │"+W+"    "+bold+"PLAN"+W+"          仅开放只读工具(计划模式)");
        System.out.println(B+"  │"+W+"                 其他操作需用户确认");
        System.out.println(B+"  │"+W+"    "+bold+"BYPASS"+W+"       全部直接执行");
        System.out.println(B+"  │"+W+"                 危险命令黑名单仍生效");
        System.out.println(B+"  │"+W);
        System.out.println(B+"  │"+W+bold+"  🔧  已注册工具 ("+tools.size()+" 个)"+W);
        for(var e:tools.byCategory().entrySet()){
            String cat=switch(e.getKey().name()){case"SEARCH"->"搜索";case"FILE"->"文件";case"SHELL"->"Shell";default->e.getKey().name();};
            System.out.println(B+"  │"+W+"    "+dim+cat+": "+reset+String.join(", ",e.getValue()));
        }
        System.out.println(B+"  │"+W);
        System.out.println(B+"  │"+W+dim+"  直接输入问题即可开始对话"+W);
        System.out.println(B+"  ╰────────────────────────────────────────────"+W);
        System.out.println();
    }

    private void startStreamingChat(String um){totalInputTokens=totalOutputTokens=currentRound=0;startTime=System.currentTimeMillis();lastEventWasTool=false;currentPreamble=new StringBuilder();mdBold=mdCode=mdPendingStar=false;mdAtLineStart=true;mdHeadingHashes=0;startSpinner("思考中");agentLoop.run(um,this::handleEvent);stopSpinner();}

    private void handleEvent(AgentEvent e){
        if(e instanceof AgentEvent.TextDelta td){if(spinnerRunning){stopSpinner();System.out.println();}if(lastEventWasTool)System.out.println();renderMarkdown(td.text());currentPreamble.append(td.text());lastEventWasTool=false;}
        else if(e instanceof AgentEvent.ToolCallStart tcs){stopSpinner();lastEventWasTool=true;}
        else if(e instanceof AgentEvent.ToolCallEnd tce){renderToolCall(tce.result(),tce.toolName());lastEventWasTool=true;}
        else if(e instanceof AgentEvent.TokenUsage tu){totalInputTokens=tu.totalInput();totalOutputTokens=tu.totalOutput();}
        else if(e instanceof AgentEvent.IterationProgress ip){currentRound=ip.round();}
        else if(e instanceof AgentEvent.RoundComplete rc){if(currentPreamble.length()>0)currentPreamble.setLength(0);}
        else if(e instanceof AgentEvent.Error err){stopSpinner();System.err.println(red+"[错误] "+err.message()+reset);}
        else if(e instanceof AgentEvent.PermissionAsk ask){handlePermissionAsk(ask);}
        else if(e instanceof AgentEvent.AgentFinished af){if(mdBold){System.out.print(boldOff);mdBold=false;}if(mdCode){System.out.print(reset);mdCode=false;}double el=(System.currentTimeMillis()-startTime)/1000.0;System.out.print(" "+dim+"("+String.format("%.1f",el)+"s"+reset);if(af.totalInputTokens()>0||af.totalOutputTokens()>0)System.out.print(dim+" · "+af.totalInputTokens()+"+"+af.totalOutputTokens()+" tokens"+reset);System.out.println(")");}
    }

    private void handlePermissionAsk(AgentEvent.PermissionAsk ask){
        String[] opts={"允许本次","永久","拒绝"};int sel=0;
        System.out.println(dim+"  \u256d\u2500 权限确认 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"+reset);
        System.out.println(dim+"  \u2502 工具: "+reset+bold+ask.toolName()+reset);
        System.out.println(dim+"  \u2502 参数: "+reset+ask.preview());
        System.out.println(dim+"  \u2502 原因: "+reset+ask.reason());
        System.out.println(dim+"  \u2570\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"+reset);
        try{while(true){
            // Print all 3 options on separate lines + help line, then go back 4 lines
            for(int i=0;i<opts.length;i++){
                System.out.print("\r\033[K  "+(i==sel?bold+"\u25b6 "+opts[i]+reset:dim+"  "+opts[i]+reset)+"\n");
            }
            System.out.print("\r\033[K  "+dim+"[\u2191\u2193/Enter/1-3]"+reset+"\n");
            System.out.flush();
            try{Thread.sleep(60);}catch(InterruptedException ie){}
            int ch=System.in.read();
            if(ch=='\n'||ch=='\r')break;
            else if(ch=='1'){sel=0;break;}else if(ch=='2'){sel=1;break;}else if(ch=='3'){sel=2;break;}
            else if(ch==27){if(System.in.available()>0){int c2=System.in.read();if(c2==91){int c3=System.in.read();if(c3==65)sel=(sel+2)%3;else if(c3==66)sel=(sel+1)%3;}}else{sel=2;break;}}
            // Move up 4 lines for next redraw
            System.out.print("\033[4A"); System.out.flush();
        }
        while(System.in.available()>0)System.in.read();
        System.out.print("\033[1A\r\033[K"); // clear last help line
        ask.future().complete(switch(sel){case 0->"allow";case 1->"permanent";default->"deny";});
        }catch(Exception ex){ask.future().complete("deny");}}

    private void renderMarkdown(String ch){for(int i=0;i<ch.length();i++){char c=ch.charAt(i);if(mdAtLineStart&&c=='#'){mdHeadingHashes++;continue;}if(mdHeadingHashes>0&&c==' '){System.out.print(bold);mdBold=true;mdHeadingHashes=0;mdAtLineStart=false;continue;}if(mdHeadingHashes>0&&c!='#'&&c!=' '){for(int j=0;j<mdHeadingHashes;j++)System.out.print('#');mdHeadingHashes=0;}mdAtLineStart=false;if(c=='*'){if(mdPendingStar){mdPendingStar=false;System.out.print(mdBold?boldOff:bold);mdBold=!mdBold;}else{mdPendingStar=true;}continue;}if(mdPendingStar){System.out.print('*');mdPendingStar=false;}if(c=='`'){System.out.print(mdCode?reset:yellow);mdCode=!mdCode;continue;}System.out.print(c);if(c=='\n'){mdAtLineStart=true;mdHeadingHashes=0;}}System.out.flush();}

    private void renderToolCall(ToolResult r,String n){String ic=switch(n){case"read_file","write_file","edit_file"->"📄";case"exec_command"->"⚡";default->"🔍";};String co=switch(n){case"read_file","find_files","grep_code"->blue;case"write_file","edit_file"->yellow;case"exec_command"->magenta;default->reset;};System.out.println("  "+(r.success()?green+"✓":red+"✗")+" "+dim+ic+reset+" "+co+n+reset+dim+"  "+r.durationMs()+"ms"+reset);}

    private void startSpinner(String l){spinnerRunning=true;System.out.print(dim+"  "+l+"  "+reset);System.out.flush();Thread t=new Thread(()->{int i=0;try{while(spinnerRunning&&!Thread.currentThread().isInterrupted()){String s=currentRound>0?"  第"+currentRound+"/10轮 "+l+" "+SPINNER[i%SPINNER.length]:"  "+l+" "+SPINNER[i%SPINNER.length];System.out.print("\r"+dim+s+reset);System.out.flush();i++;Thread.sleep(120);}}catch(InterruptedException ie){}});t.setDaemon(true);t.start();this.spinnerThread=t;}
    private void stopSpinner(){spinnerRunning=false;if(spinnerThread!=null){try{spinnerThread.join(200);}catch(InterruptedException ie){}}spinnerThread=null;System.out.print("\r\033[K");System.out.flush();}
}
