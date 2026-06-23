package com.easycode.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** 小组磁盘持久化：~/.easycode/teams/<name>/ */
public final class TeamPersistence {

    private static final ObjectMapper json = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path TEAMS_DIR = Path.of(System.getProperty("user.home"), ".easycode/teams");

    private TeamPersistence() {}

    /** 保存小组到磁盘 */
    public static void save(Team team) throws IOException {
        Path teamDir = TEAMS_DIR.resolve(team.name());
        Files.createDirectories(teamDir);

        // team.json
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", team.name());
        data.put("leadName", team.leadName());
        List<Map<String, Object>> memberList = new ArrayList<>();
        for (TeamMember m : team.members()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("name", m.name());
            mm.put("workDir", m.workDir() != null ? m.workDir().toString() : null);
            mm.put("backend", m.backend().name());
            mm.put("requireApproval", m.requireApproval());
            memberList.add(mm);
        }
        data.put("members", memberList);
        json.writeValue(teamDir.resolve("team.json").toFile(), data);

        // tasks.jsonl（空文件，如果不存在）
        Path tasksFile = teamDir.resolve("tasks.jsonl");
        if (!Files.exists(tasksFile)) {
            Files.createFile(tasksFile);
        }

        // mailboxes/ 目录 + 各成员邮箱文件
        Path mailboxesDir = teamDir.resolve("mailboxes");
        Files.createDirectories(mailboxesDir);
        for (TeamMember m : team.members()) {
            Path mailboxFile = mailboxesDir.resolve(m.name() + ".jsonl");
            if (!Files.exists(mailboxFile)) {
                Files.createFile(mailboxFile);
            }
            m.setMailboxPath(mailboxFile);
        }
    }

    /** 从磁盘加载小组 */
    @SuppressWarnings("unchecked")
    public static Team load(String teamName) throws IOException {
        Path teamDir = TEAMS_DIR.resolve(teamName);
        if (!Files.isDirectory(teamDir)) {
            throw new IOException("小组不存在: " + teamName);
        }

        Map<String, Object> data = json.readValue(teamDir.resolve("team.json").toFile(), Map.class);
        String name = (String) data.get("name");
        String leadName = (String) data.get("leadName");

        Team team = new Team(name, leadName, teamDir);
        List<Map<String, Object>> memberList = (List<Map<String, Object>>) data.get("members");
        if (memberList != null) {
            for (Map<String, Object> mm : memberList) {
                String mName = (String) mm.get("name");
                Path workDir = mm.get("workDir") != null ? Path.of((String) mm.get("workDir")) : null;
                RuntimeBackend backend = RuntimeBackend.valueOf((String) mm.get("backend"));
                boolean requireApproval = (boolean) mm.getOrDefault("requireApproval", false);
                TeamMember member = new TeamMember(mName, workDir, backend, requireApproval);

                // 恢复邮箱路径
                Path mailboxFile = teamDir.resolve("mailboxes").resolve(mName + ".jsonl");
                if (Files.exists(mailboxFile)) {
                    member.setMailboxPath(mailboxFile);
                }
                team.addMember(member);
            }
        }

        return team;
    }

    /** 列出所有小组名 */
    public static List<String> listTeams() throws IOException {
        if (!Files.isDirectory(TEAMS_DIR)) return List.of();
        try (Stream<Path> entries = Files.list(TEAMS_DIR)) {
            return entries.filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .toList();
        }
    }

    /** 删除小组 */
    public static void delete(String teamName) throws IOException {
        Path teamDir = TEAMS_DIR.resolve(teamName);
        if (Files.isDirectory(teamDir)) {
            try (Stream<Path> walk = Files.walk(teamDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ignored) {}
                    });
            }
        }
    }

    /** 获取小组数据目录 */
    public static Path teamsDir() { return TEAMS_DIR; }
}
