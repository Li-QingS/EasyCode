package com.easycode.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PermissionConfig {
    private static final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public static RuleEngine load(Path projectRoot) {
        List<RuleEngine.Rule> rules = new ArrayList<>();
        String defaultMode = null;

        // 用户级 ~/.easycode/permissions.yaml
        String home = System.getProperty("user.home");
        Path userPath = (home != null) ? Path.of(home, ".easycode", "permissions.yaml") : Path.of("/tmp/.easycode/permissions.yaml");
        String[] userDM = new String[1];
        loadFile(userPath, rules, userDM);
        if (userDM[0] != null) defaultMode = userDM[0];

        // 项目级 <project>/.easycode/permissions.yaml
        Path projPath = projectRoot.resolve(".easycode/permissions.yaml");
        String[] projDM = new String[1];
        loadFile(projPath, rules, projDM);
        if (projDM[0] != null) defaultMode = projDM[0];

        // 本地级 easycode.permissions.yaml（与 easycode.yaml 同目录）
        Path localPath = Path.of("easycode.permissions.yaml");
        String[] localDM = new String[1];
        loadFile(localPath, rules, localDM);
        if (localDM[0] != null) defaultMode = localDM[0];

        return new RuleEngine(rules, defaultMode);
    }

    private static void loadFile(Path path, List<RuleEngine.Rule> rules, String[] defaultModeHolder) {
        File f = path.toFile();
        if (!f.exists()) return;
        try {
            JsonNode root = yaml.readTree(f);
            if (root.has("defaultMode"))
                defaultModeHolder[0] = root.get("defaultMode").asText();
            if (root.has("rules")) {
                for (JsonNode r : root.get("rules")) {
                    String entry = r.asText().trim();
                    if (entry.isEmpty()) continue;
                    rules.add(RuleEngine.Rule.parse(entry,
                        path.toString().contains(".easycode") ? RuleEngine.Rule.RuleLevel.PROJECT : RuleEngine.Rule.RuleLevel.LOCAL));
                }
            }
        } catch (IOException e) { /* 降级：跳过非法文件 */ }
    }
}
