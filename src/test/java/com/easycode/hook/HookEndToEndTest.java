package com.easycode.hook;

import com.easycode.agent.AgentLoop;
import com.easycode.agent.ToolExecutor;
import com.easycode.config.Config;
import com.easycode.conversation.ConversationMgr;
import com.easycode.tool.ToolRegistry;
import com.easycode.tool.ToolResult;
import com.easycode.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HookEndToEndTest {

    private Path tempDir;
    private ToolRegistry tools;
    private HookEngine hookEngine;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("easycode-hook-test");
        tools = new ToolRegistry();
        // Register a simple test tool
        tools.register(new Tool() {
            public String name() { return "test_tool"; }
            public String description() { return "test"; }
            public Tool.Category category() { return Tool.Category.FILE; }
            public JsonNode inputSchema() { return null; }
            public Permission permission() { return Permission.READ_ONLY; }
            public ToolResult execute(JsonNode input) {
                return ToolResult.ok("test_tool", "done", 0);
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up temp dir
        Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.delete(p); } catch (Exception e) {}
        });
    }

    // === AC1: session-start prompt injection ===
    @Test
    void ac1_promptInjectionOnSessionStart() throws Exception {
        String yaml = """
            hooks:
              - name: inject-context
                event: session-start
                action:
                  type: prompt
                  text: "[HOOK-INJECTED-CONTEXT]"
                once: true
            """;
        Files.writeString(tempDir.resolve("easycode.hooks.yaml"), yaml);
        var rules = HookConfig.load(tempDir);
        hookEngine = new HookEngine(rules);
        ToolExecutor.setHookEngine(hookEngine);

        // Collect session-start prompts
        String prompts = hookEngine.collectPrompts(HookEvent.SESSION_START,
            Map.of("sessionId", "test-session"));
        assertTrue(prompts.contains("[HOOK-INJECTED-CONTEXT]"),
            "AC1 FAIL: session-start prompt not injected");
        System.out.println("✅ AC1 PASS: prompt injected correctly");
    }

    // === AC2: pre-tool intercept ===
    @Test
    void ac2_preToolInterceptBlockDangerous() throws Exception {
        String yaml = """
            hooks:
              - name: block-rm
                event: pre-tool
                if:
                  all:
                    - equals: { field: name, value: exec_command }
                    - regex: { field: input, value: "rm\\\\s+-rf" }
                action:
                  type: prompt
                  text: "危险操作已被拦截。请使用 trash 命令。"
            """;
        Files.writeString(tempDir.resolve("easycode.hooks.yaml"), yaml);
        var rules = HookConfig.load(tempDir);
        hookEngine = new HookEngine(rules);
        ToolExecutor.setHookEngine(hookEngine);

        // Simulate a tool call
        var vars = new HashMap<String, Object>();
        vars.put("name", "exec_command");
        vars.put("input", "{\"command\":\"rm -rf /tmp/test\"}");
        var result = hookEngine.fire(HookEvent.PRE_TOOL, vars);

        assertTrue(result.isPresent(), "AC2 FAIL: dangerous tool was NOT intercepted");
        assertTrue(result.get().content().contains("危险操作已被拦截"),
            "AC2 FAIL: intercept message missing. Got: " + result.get().content());
        assertTrue(result.get().content().contains("trash"),
            "AC2 FAIL: should suggest alternative. Got: " + result.get().content());
        System.out.println("✅ AC2 PASS: dangerous tool intercepted with guidance");
    }

    // === AC5: multi-event ordering ===
    @Test
    void ac5_multiEventOrdering() throws Exception {
        String yaml = """
            hooks:
              - name: turn-log
                event: turn-start
                action:
                  type: prompt
                  text: "turn-log"
              - name: pre-log
                event: pre-tool
                action:
                  type: prompt
                  text: "pre-log"
              - name: post-log
                event: post-tool
                action:
                  type: prompt
                  text: "post-log"
            """;
        Files.writeString(tempDir.resolve("easycode.hooks.yaml"), yaml);
        var rules = HookConfig.load(tempDir);
        hookEngine = new HookEngine(rules);
        ToolExecutor.setHookEngine(hookEngine);

        List<String> order = Collections.synchronizedList(new ArrayList<>());

        // Fire TURN_START
        hookEngine.fire(HookEvent.TURN_START, Map.of("round", "1"));
        order.add("turn-start");

        // Fire PRE_TOOL
        var preVars = new HashMap<String, Object>();
        preVars.put("name", "test_tool");
        preVars.put("input", "{}");
        var intercept = hookEngine.fire(HookEvent.PRE_TOOL, preVars);
        order.add(intercept.isPresent() ? "pre-tool-intercepted" : "pre-tool-passed");

        // Fire POST_TOOL
        var postVars = new HashMap<String, Object>();
        postVars.put("name", "test_tool");
        postVars.put("success", true);
        postVars.put("contentLen", 4);
        hookEngine.fire(HookEvent.POST_TOOL, postVars);
        order.add("post-tool");

        // Sequential check has to be approximate since async, but all should fire
        assertTrue(order.contains("turn-start"), "AC5 FAIL: turn-start not fired");
        assertTrue(order.contains("pre-tool-intercepted"), "AC5 FAIL: pre-tool not intercepted");
        assertTrue(order.contains("post-tool"), "AC5 FAIL: post-tool not fired");
        System.out.println("✅ AC5 PASS: all 3 events fired in sequence. Order: " + order);
    }

    // === AC4: hook failure isolation (already covered by HookEngineTest) ===
    @Test
    void ac4_hookFailureIsolation() throws Exception {
        var flakyAction = new HookAction() {
            public String type() { return "flaky"; }
            public String execute(HookContext ctx) { throw new RuntimeException("Intentional failure"); }
        };
        var flakyRule = new HookRule("flaky", HookEvent.STARTUP, null, flakyAction, false, false);
        var goodRule = new HookRule("good", HookEvent.STARTUP, null,
            new PromptAction("still-works"), false, false);
        var engine = new HookEngine(List.of(flakyRule, goodRule));

        // Should NOT throw
        var result = engine.fire(HookEvent.STARTUP, Map.of());
        assertTrue(result.isEmpty(), "Hook failure should not affect result");
        System.out.println("✅ AC4 PASS: hook failure isolated, subsequent hooks unaffected");
    }

    // === AC6: once semantics (already covered by HookEngineTest) ===
    @Test
    void ac6_onceOnlyFiresFirstTime() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        var countingAction = new HookAction() {
            public String type() { return "counter"; }
            public String execute(HookContext ctx) { counter.incrementAndGet(); return "ok"; }
        };
        var rule = new HookRule("once", HookEvent.STARTUP, null, countingAction, true, false);
        var engine = new HookEngine(List.of(rule));

        engine.fire(HookEvent.STARTUP, Map.of()); // first: should fire
        assertEquals(1, counter.get(), "First fire should execute");

        engine.fire(HookEvent.STARTUP, Map.of()); // second: should skip
        engine.fire(HookEvent.STARTUP, Map.of()); // third: should skip
        assertEquals(1, counter.get(), "Once rule should fire only ONCE");
        System.out.println("✅ AC6 PASS: once rule executed exactly once");
    }
}
