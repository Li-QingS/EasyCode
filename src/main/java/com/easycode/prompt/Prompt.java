package com.easycode.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 模块化系统提示装配器 */
public final class Prompt {

    private Prompt() {}

    /** 七个固定模块，按 priority 升序（值越小越靠前） */
    public static List<Module> fixedModules() {
        return List.of(
            new Module("身份", 10, "你是 EasyCode，一个终端 AI 编程助手。"),
            new Module("系统约束", 20,
                "路径统一用正斜杠 /。用户要求模糊时反问确认。工具结果截断时用 offset 继续读。"),
            new Module("任务模式", 30, ""),
            new Module("动作执行", 40,
                "优先凭自身知识回答；需要准确文件内容、项目结构或代码细节时再使用工具。"),
            new Module("工具使用", 50,
                "编辑文件前必须先 read_file 读取；优先用专用工具(read_file/find_files/grep_code)而非 exec_command 拼凑 shell。"),
            new Module("语气风格", 60, "用中文回答，简洁直接。"),
            new Module("文本输出", 70, "每次回答必须以文字形式给出结论，不允许只调工具不说话。")
        );
    }

    /** 三个可选空槽，留待后续章节填充 */
    public static List<Module> optionalModules() {
        return List.of(
            new Module("自定义指令", 80, ""),
            new Module("已激活 Skill", 90, ""),
            new Module("长期记忆", 100, "")
        );
    }

    /** 按 priority 升序排列，跳过 content 为空的模块，以 "\n\n" 连接 */
    public static String assemble(List<Module> modules) {
        return modules.stream()
            .filter(m -> !m.content().isEmpty())
            .sorted(Comparator.comparingInt(Module::priority))
            .map(Module::content)
            .collect(Collectors.joining("\n\n"));
    }

    /** 构建稳定系统提示（固定模块 + 可选模块，不含环境信息） */
    public static String buildStable() { return buildSystemPrompt("", ""); }
    public static String buildSystemPrompt(String instructions, String memory) {
        return buildWithSkills(instructions, memory, null);
    }

    /** 构建系统提示，含已激活 Skill（填入 priority 90 槽位）。activatedSkills 为 null 时不填充。 */
    public static String buildWithSkills(String instructions, String memory, String activatedSkills) {
        List<Module> all = new ArrayList<>();
        all.addAll(fixedModules());
        all.addAll(optionalModules());
        if (instructions != null && !instructions.isBlank()) all.add(new Module("custom-instructions", 80, instructions));
        if (memory != null && !memory.isBlank()) all.add(new Module("long-term-memory", 100, memory));
        // 已激活 Skill 填充
        if (activatedSkills != null && !activatedSkills.isBlank()) {
            all.removeIf(m -> m.name().equals("已激活 Skill"));
            all.add(new Module("已激活 Skill", 90, activatedSkills));
        }
        return assemble(all);
    }
}
