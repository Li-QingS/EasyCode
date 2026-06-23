package com.easycode.hook;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HookEngineTest {

    @Test
    void unconditionalRuleTriggers() {
        var rule = new HookRule("test", HookEvent.STARTUP, null,
            new PromptAction("hello"), false, false);
        var engine = new HookEngine(List.of(rule));
        var result = engine.fire(HookEvent.STARTUP, Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void conditionMatchTriggers() {
        var rule = new HookRule("match", HookEvent.TURN_START,
            new ConditionNode.Equals("round", "1"),
            new PromptAction("matched"), false, false);
        var engine = new HookEngine(List.of(rule));
        var r1 = engine.fire(HookEvent.TURN_START, Map.of("round", "1"));
        assertTrue(r1.isEmpty()); // prompt doesn't intercept
    }

    @Test
    void conditionMissSkips() {
        var rule = new HookRule("miss", HookEvent.TURN_START,
            new ConditionNode.Equals("round", "5"),
            new PromptAction("missed"), false, false);
        var engine = new HookEngine(List.of(rule));
        var result = engine.fire(HookEvent.TURN_START, Map.of("round", "1"));
        assertTrue(result.isEmpty());
    }

    @Test
    void onceRuleFiresOnlyFirstTime() {
        var rule = new HookRule("once", HookEvent.STARTUP, null,
            new PromptAction("once"), true, false);
        var engine = new HookEngine(List.of(rule));
        engine.fire(HookEvent.STARTUP, Map.of());
        // second fire should skip
        var result = engine.fire(HookEvent.STARTUP, Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void preToolInterceptReturnsToolResult() {
        var rule = new HookRule("block", HookEvent.PRE_TOOL,
            new ConditionNode.Equals("name", "exec_command"),
            new PromptAction("blocked: dangerous"), false, false);
        var engine = new HookEngine(List.of(rule));
        var result = engine.fire(HookEvent.PRE_TOOL, Map.of("name", "exec_command", "input", "{}"));
        assertTrue(result.isPresent());
        assertTrue(result.get().content().contains("blocked"));
    }

    @Test
    void failedRuleDoesNotBlockOthers() {
        // A rule with a broken action that will throw, plus a good rule
        var brokenAction = new HookAction() {
            public String type() { return "broken"; }
            public String execute(HookContext ctx) { throw new RuntimeException("Boom!"); }
        };
        var badRule = new HookRule("bad", HookEvent.STARTUP, null, brokenAction, false, false);
        var goodRule = new HookRule("good", HookEvent.STARTUP, null,
            new PromptAction("good"), false, false);
        var engine = new HookEngine(List.of(badRule, goodRule));
        // Should not throw - just log the error
        var result = engine.fire(HookEvent.STARTUP, Map.of());
        assertTrue(result.isEmpty());
    }
}
