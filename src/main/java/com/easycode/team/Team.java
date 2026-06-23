package com.easycode.team;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 长期存在的小组 */
public class Team {
    private final String name;
    private final String leadName;
    private final List<TeamMember> members;
    private final Path storageDir;

    public Team(String name, String leadName, Path storageDir) {
        this.name = name;
        this.leadName = leadName;
        this.storageDir = storageDir;
        this.members = new ArrayList<>();
    }

    public String name() { return name; }
    public String leadName() { return leadName; }
    public List<TeamMember> members() { return members; }
    public Path storageDir() { return storageDir; }

    public void addMember(TeamMember member) { members.add(member); }

    public TeamMember getMember(String name) {
        return members.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
    }

    @Override
    public String toString() {
        return "Team{name='" + name + "', lead='" + leadName + "', members=" + members.size() + "}";
    }
}
