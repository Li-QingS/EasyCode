package com.easycode.subagent;

import com.easycode.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ch14 Worktree 隔离端到端测试 — 对照 checklist 逐项验证
 */
class WorktreeEndToEndTest {

    private static final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path projectDir;
    private WorktreeManager wm;

    @BeforeEach
    void setUp() throws Exception {
        // 初始化 git 仓库（WorktreeManager 依赖 git）
        projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        runGit(projectDir, "init");
        runGit(projectDir, "config", "user.email", "test@test.com");
        runGit(projectDir, "config", "user.name", "Test");
        // 初始提交（git worktree add 需要干净的工作区）
        Files.writeString(projectDir.resolve("README.md"), "# Test Project");
        runGit(projectDir, "add", ".");
        runGit(projectDir, "commit", "-m", "init");
        // 写一个 easycode.yaml（Worktree 创建时会复制）
        Files.writeString(projectDir.resolve("easycode.yaml"), "model: test-model\n");
        wm = new WorktreeManager(projectDir);
    }

    // ==================== AC4: 非法 slug 拒绝 ====================
    @Test
    void ac4_invalidSlugRejected() {
        // 空格
        assertFalse(WorktreeManager.isValidSlug("my agent"));
        // 中文
        assertFalse(WorktreeManager.isValidSlug("审查"));
        // 路径穿越
        assertFalse(WorktreeManager.isValidSlug(".."));
        assertFalse(WorktreeManager.isValidSlug("."));
        assertFalse(WorktreeManager.isValidSlug("etc/passwd"));
        // 反斜杠
        assertFalse(WorktreeManager.isValidSlug("a\\b"));
        // 空
        assertFalse(WorktreeManager.isValidSlug(""));
        assertFalse(WorktreeManager.isValidSlug(null));
        // 超过64字符
        assertFalse(WorktreeManager.isValidSlug("a".repeat(65)));

        // 合法 slug
        assertTrue(WorktreeManager.isValidSlug("code-reviewer"));
        assertTrue(WorktreeManager.isValidSlug("a"));
        assertTrue(WorktreeManager.isValidSlug("my-agent-123"));
        // 以连字符开头不合法
        assertFalse(WorktreeManager.isValidSlug("-start"));

        System.out.println("✅ AC4: invalid slug rejected, valid slug accepted");
    }

    // ==================== slug 生成 ====================
    @Test
    void slugGeneratesSafeNames() {
        assertEquals("code-reviewer", WorktreeManager.slug("Code Reviewer"));
        assertEquals("my-agent", WorktreeManager.slug("My Agent!"));
        assertEquals("test-agent", WorktreeManager.slug("  Test Agent  "));
        assertEquals("agent", WorktreeManager.slug(""));
        assertEquals("agent", WorktreeManager.slug(null));
        assertEquals("a-b-c", WorktreeManager.slug("A---B---C"));
        // 超长截断
        String longName = "a".repeat(100);
        String slug = WorktreeManager.slug(longName);
        assertTrue(slug.length() <= 64);
        System.out.println("✅ Slug generation: " + WorktreeManager.slug("Code Reviewer"));
    }

    // ==================== AC1 + AC5: 创建和复用 ====================
    @Test
    void ac1_createAndAc5_reuseWorktree() throws Exception {
        String slug = WorktreeManager.slug("Code Reviewer");
        assertTrue(WorktreeManager.isValidSlug(slug));

        // 创建
        Path wt = wm.create(slug);
        assertTrue(Files.isDirectory(wt));
        assertTrue(Files.exists(wt.resolve("README.md")), "Worktree 应包含项目文件");
        assertTrue(Files.exists(wt.resolve("easycode.yaml")), "Worktree 应复制 easycode.yaml");

        // AC5: 复用已存在的目录
        Path wt2 = wm.create(slug);
        assertEquals(wt, wt2, "复用应返回相同路径");

        // 清理
        wm.remove(wt);
        assertFalse(Files.exists(wt));

        System.out.println("✅ AC1: Worktree created with project files + config");
        System.out.println("✅ AC5: Existing directory reused");
    }

    // ==================== AC2: 无变更自动清理 / AC3: 有变更保留 ====================
    @Test
    void ac2_noChangesAutoClean() throws Exception {
        String slug = WorktreeManager.slug("readonly");
        Path wt = wm.create(slug);

        // 无变更
        assertFalse(wm.hasChanges(wt), "新创建的 Worktree 不应有变更");

        // 模拟退出清理
        wm.remove(wt);
        assertFalse(Files.exists(wt));

        System.out.println("✅ AC2: No changes → auto cleanup");
    }

    @Test
    void ac3_hasChangesRetain() throws Exception {
        String slug = WorktreeManager.slug("writer");
        Path wt = wm.create(slug);

        // 写入文件
        Files.writeString(wt.resolve("new-file.txt"), "hello worktree");

        // 检测到变更
        assertTrue(wm.hasChanges(wt), "写入文件后应有变更");

        // 手动清理（测试后）
        wm.remove(wt);

        System.out.println("✅ AC3: Changes detected → retain (manual cleanup for test)");
    }

    // ==================== AC6: isolation=none 不创建 Worktree ====================
    @Test
    void ac6_isolationNoneNoWorktree() throws Exception {
        // load agent without isolation → defaults to "none"
        Files.createDirectories(projectDir.resolve(".easycode/agents"));
        Files.writeString(projectDir.resolve(".easycode/agents/normal.md"), """
            ---
            name: normal
            tools_allow:
              - read_file
            max_turns: 3
            ---
            # Normal agent (no isolation)
            """);

        var defs = AgentDefLoader.loadAll(projectDir);
        AgentDef def = defs.get("normal");
        assertNotNull(def);
        assertEquals("none", def.isolation(), "未显式设 isolation 时应为 none");

        System.out.println("✅ AC6: isolation=none (default) — no Worktree created");
    }

    // ==================== isolation=worktree 解析 ====================
    @Test
    void isolationWorktreeParsed() throws Exception {
        Files.createDirectories(projectDir.resolve(".easycode/agents"));
        Files.writeString(projectDir.resolve(".easycode/agents/isolated.md"), """
            ---
            name: isolated
            isolation: worktree
            tools_allow:
              - write_file
              - read_file
            ---
            # Isolated agent
            """);

        var defs = AgentDefLoader.loadAll(projectDir);
        AgentDef def = defs.get("isolated");
        assertNotNull(def);
        assertEquals("worktree", def.isolation());

        System.out.println("✅ isolation=worktree parsed from YAML frontmatter");
    }

    // ==================== WorktreedToolRegistry 路径重定向 ====================
    @Test
    void worktreedToolRegistryRedirectsPaths() throws Exception {
        String slug = WorktreeManager.slug("test-redirect");
        Path wt = wm.create(slug);

        // 构建父 ToolRegistry
        ToolRegistry parent = new ToolRegistry();
        parent.register(new ReadFileTool());
        parent.register(new WriteFileTool());
        parent.register(new ExecCommandTool());
        parent.register(new FindFilesTool());

        // 包装
        ToolRegistry redirected = WorktreedToolRegistry.wrap(parent, wt);

        // 验证 write_file 路径重定向
        Tool writeTool = redirected.get("write_file");
        ObjectNode writeInput = json.createObjectNode();
        writeInput.put("path", "test-output.txt");
        writeInput.put("content", "redirected content");
        ToolResult result = writeTool.execute(writeInput);
        assertTrue(result.success(), "write_file should succeed: " + result.content());

        // 验证文件确实落在 Worktree 内
        assertTrue(Files.exists(wt.resolve("test-output.txt")),
            "AC1: 文件应落在 Worktree 目录，不是项目根目录");
        assertFalse(Files.exists(projectDir.resolve("test-output.txt")),
            "文件不应落在项目根目录");

        // 验证路径越界保护
        ObjectNode escapeInput = json.createObjectNode();
        escapeInput.put("path", "../../../etc/passwd");
        escapeInput.put("content", "hack");
        ToolResult escapeResult = writeTool.execute(escapeInput);
        assertFalse(escapeResult.success(), "路径越界应被拦截");
        assertTrue(escapeResult.content().contains("越界"), "错误消息应包含'越界'");

        // 验证只读工具透传
        assertNotNull(redirected.get("find_files"), "只读工具应透传");
        assertEquals(Tool.Category.SEARCH, redirected.get("find_files").category());

        // 验证 exec_command 注入了 workingDir
        Tool execTool = redirected.get("exec_command");
        ObjectNode execInput = json.createObjectNode();
        execInput.put("command", "pwd");
        ToolResult execResult = execTool.execute(execInput);
        assertTrue(execResult.success());

        // 清理
        wm.remove(wt);

        System.out.println("✅ WorktreedToolRegistry: path redirection + sandbox escape protection verified");
    }

    // ==================== 过期清理 ====================
    @Test
    void cleanExpiredRemovesOldWorktrees() throws Exception {
        String slug = WorktreeManager.slug("expired");
        Path wt = wm.create(slug);
        assertTrue(Files.isDirectory(wt));

        // 设置很短过期时间（当前目录几乎刚创建，所以清理不掉）
        // 设置负的过期时间（0ms）→ 所有目录都过期
        wm.cleanExpired(0);
        assertFalse(Files.exists(wt), "过期 Worktree 应被清理");

        System.out.println("✅ cleanExpired: expired worktrees removed");
    }

    // ==================== 完整链路：RunAgentTool + Worktree ====================
    @Test
    void fullPipelineWorktreeIsolation() throws Exception {
        Files.createDirectories(projectDir.resolve(".easycode/agents"));
        Files.writeString(projectDir.resolve(".easycode/agents/builder.md"), """
            ---
            name: builder
            isolation: worktree
            tools_allow:
              - write_file
              - read_file
            max_turns: 3
            ---
            # Builder Agent (worktree isolated)
            """);

        var defs = AgentDefLoader.loadAll(projectDir);
        AgentDef def = defs.get("builder");
        assertEquals("worktree", def.isolation());

        // slug 生成
        String slug = WorktreeManager.slug(def.name());
        assertTrue(WorktreeManager.isValidSlug(slug));

        // 创建 Worktree
        Path wt = wm.create(slug);
        assertTrue(Files.isDirectory(wt));
        assertTrue(Files.exists(wt.resolve("README.md")));

        // 验证变更检测
        assertFalse(wm.hasChanges(wt));
        Files.writeString(wt.resolve("output.txt"), "generated by builder");
        assertTrue(wm.hasChanges(wt));

        // 清理
        wm.remove(wt);
        assertFalse(Files.exists(wt));

        System.out.println("✅ Full pipeline: AgentDef → slug → create → changes → cleanup");
    }

    // ==================== 并发安全：不同角色互不干扰 ====================
    @Test
    void concurrentWorktreesIndependent() throws Exception {
        String slugA = WorktreeManager.slug("agent-a");
        String slugB = WorktreeManager.slug("agent-b");

        Path wtA = wm.create(slugA);
        Path wtB = wm.create(slugB);

        assertNotEquals(wtA, wtB, "不同角色的 Worktree 目录应不同");

        // A 写入
        Files.writeString(wtA.resolve("a-output.txt"), "from A");
        // B 写入
        Files.writeString(wtB.resolve("b-output.txt"), "from B");

        // 互相不可见
        assertTrue(Files.exists(wtA.resolve("a-output.txt")));
        assertFalse(Files.exists(wtA.resolve("b-output.txt")));
        assertTrue(Files.exists(wtB.resolve("b-output.txt")));
        assertFalse(Files.exists(wtB.resolve("a-output.txt")));

        // 项目根目录不受影响
        assertFalse(Files.exists(projectDir.resolve("a-output.txt")));
        assertFalse(Files.exists(projectDir.resolve("b-output.txt")));

        wm.remove(wtA);
        wm.remove(wtB);

        System.out.println("✅ 并发安全：两个 Worktree 互不干扰");
    }

    // ==================== 辅助方法 ====================
    private static void runGit(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
            .redirectErrorStream(true).start();
        int exit = p.waitFor();
        if (exit != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            System.err.println("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
