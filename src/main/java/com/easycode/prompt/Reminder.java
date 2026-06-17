package com.easycode.prompt;

/** system-reminder 补充消息构造器 */
public final class Reminder {

    private static final int PLAN_REMINDER_INTERVAL = 4;

    private Reminder() {}

    /** 用 <system-reminder> 标签包裹消息体 */
    public static String wrap(String body) {
        return "<system-reminder>\n" + body + "\n</system-reminder>";
    }

    /** 规划模式提醒：full=true 完整版，false 精简版 */
    public static String planReminder(boolean full) {
        if (full) {
            return wrap("你处于规划模式。只允许使用只读工具产出计划，不要修改任何文件。"
                    + "用 find_files / grep_code / read_file 收集信息后，用文字描述你的执行计划。"
                    + "计划应包含：需要改哪些文件、每个文件改什么、为什么这样改。");
        }
        return wrap("规划模式（只读）。继续用文字产出计划。");
    }

    /** /do 切换时注入的执行指令 */
    public static final String EXECUTE_DIRECTIVE = "已切换到执行模式。请按上文计划立即开始执行，允许使用全部工具。";

    /** 计算本轮是否需要完整提醒 */
    public static boolean isFullReminder(int round) {
        return round == 1 || (round - 1) % PLAN_REMINDER_INTERVAL == 0;
    }
}
