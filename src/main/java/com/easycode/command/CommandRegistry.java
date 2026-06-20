package com.easycode.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 命令注册中心：管理命令元数据，启动阶段检测别名冲突（冲突则抛异常退出）。
 * 支持按名称/别名查找、Tab 补全候选生成、已注册命令列表。
 */
public final class CommandRegistry {
    private final Map<String, CommandDef> byName = new LinkedHashMap<>();
    private final Map<String, String> aliasToName = new LinkedHashMap<>();
    private volatile boolean sealed = false;

    /** 注册一条命令。名称或别名冲突时抛出 {@link IllegalStateException}。 */
    public void register(CommandDef def) {
        if (sealed) throw new IllegalStateException("Registry is sealed; cannot register after startup");
        String name = def.name().toLowerCase();
        if (byName.containsKey(name)) {
            throw new IllegalStateException(
                "命令名冲突: '" + name + "' 已被 " + byName.get(name).name() + " 注册");
        }
        for (String alias : def.aliases()) {
            String al = alias.toLowerCase();
            if (byName.containsKey(al)) {
                throw new IllegalStateException(
                    "别名 '" + alias + "' 与已注册命令名 '" + al + "' 冲突");
            }
            if (aliasToName.containsKey(al)) {
                throw new IllegalStateException(
                    "别名冲突: '" + alias + "' 已被 " + aliasToName.get(al) + " 使用");
            }
        }
        byName.put(name, def);
        for (String alias : def.aliases()) {
            aliasToName.put(alias.toLowerCase(), name);
        }
    }

    /** 批量注册 */
    public void registerAll(List<CommandDef> defs) {
        for (CommandDef d : defs) register(d);
    }

    /** 注册完成后调用：锁定注册表，禁止后续注册 */
    public void seal() { sealed = true; }
    public boolean isSealed() { return sealed; }

    /** 按命令名或别名查找（大小写不敏感）。未找到返回 null。 */
    public CommandDef lookup(String nameOrAlias) {
        String key = nameOrAlias.toLowerCase();
        CommandDef def = byName.get(key);
        if (def != null) return def;
        String canonicalName = aliasToName.get(key);
        return canonicalName != null ? byName.get(canonicalName) : null;
    }

    /**
     * 生成 Tab 补全候选列表（按字母序）。隐藏命令不参与补全。
     * 返回候选的完整名称和描述，供 Completer 使用。
     */
    public List<Candidate> complete(String partial) {
        String prefix = (partial != null) ? partial.stripLeading().toLowerCase() : "";
        if (prefix.startsWith("/")) prefix = prefix.substring(1);
        Set<String> seen = new TreeSet<>();
        List<Candidate> result = new ArrayList<>();
        for (CommandDef def : byName.values()) {
            if (def.hidden()) continue;
            String n = def.name().toLowerCase();
            if (n.startsWith(prefix)) {
                if (seen.add("/" + def.name())) {
                    result.add(new Candidate("/" + def.name(), def.description()));
                }
            }
            for (String alias : def.aliases()) {
                String al = alias.toLowerCase();
                if (al.startsWith(prefix)) {
                    if (seen.add("/" + alias)) {
                        String desc = "(→ /" + def.name() + ") " + def.description();
                        result.add(new Candidate("/" + alias, desc));
                    }
                }
            }
        }
        return result;
    }

    public record Candidate(String name, String description) {}
    public List<CommandDef> all() { return List.copyOf(byName.values()); }
    public List<CommandDef> visible() {
        return byName.values().stream().filter(d -> !d.hidden()).toList();
    }
    public int size() { return byName.size(); }
}
