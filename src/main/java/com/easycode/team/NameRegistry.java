package com.easycode.team;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 名称→邮箱路径注册表 */
public class NameRegistry {

    private final Map<String, Path> registry = new ConcurrentHashMap<>();

    /** 注册成员名称到邮箱路径 */
    public void register(String name, Path mailboxPath) {
        registry.put(name, mailboxPath);
    }

    /** 解析成员名称到邮箱文件路径 */
    public Path resolve(String name) {
        return registry.get(name);
    }

    /** 获取所有已注册名称 */
    public Iterable<String> names() {
        return registry.keySet();
    }

    /** 成员是否已注册 */
    public boolean contains(String name) {
        return registry.containsKey(name);
    }

    public int size() { return registry.size(); }

    /** 从 Team 初始化注册表 */
    public void initFromTeam(Team team) {
        for (TeamMember m : team.members()) {
            if (m.mailboxPath() != null) {
                register(m.name(), m.mailboxPath());
            }
        }
    }
}
