package com.easycode.skill;

import com.easycode.tool.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SkillLoaderTest {

    @Test
    public void testBuiltinPathNotBrokenByNormalize() {
        Path raw = Path.of("builtin:/skills/review.md");
        Path abs = raw.toAbsolutePath().normalize();
        System.out.println("raw:        " + raw);
        System.out.println("abs:        " + abs);
        System.out.println("contains?   " + abs.toString().contains("builtin:"));
        System.out.println("startsWith? " + abs.toString().startsWith("builtin:"));
        assertTrue(abs.toString().contains("builtin:"), "path should contain builtin:");
    }

    @Test
    public void testLoadBuiltinSkill() {
        ToolRegistry tr = new ToolRegistry();
        tr.register(new ExecCommandTool());
        tr.register(new ReadFileTool());
        tr.register(new GrepCodeTool());
        tr.register(new WriteFileTool());
        tr.register(new EditFileTool());
        tr.register(new FindFilesTool());

        SkillLoader loader = new SkillLoader(tr, Path.of("").toAbsolutePath());
        // Verify discovery
        List<SkillFrontmatter> frontmatters = loader.loadAll();
        assertFalse(frontmatters.isEmpty(), "should discover builtin skills");
        System.out.println("Discovered skills:");
        for (SkillFrontmatter fm : frontmatters) {
            System.out.println("  " + fm.name() + " -> " + fm.sourcePath());
        }

        // Verify loading succeeds for all
        for (SkillFrontmatter fm : frontmatters) {
            SkillDef def = loader.loadFull(fm);
            assertNotNull(def, "should load " + fm.name());
            assertFalse(def.promptBody().isBlank(), "prompt body should not be empty for " + fm.name());
            System.out.println("LOADED " + fm.name() + ": " +
                def.promptBody().substring(0, Math.min(60, def.promptBody().length())) + "...");
        }
    }
}
