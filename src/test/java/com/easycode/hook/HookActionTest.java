package com.easycode.hook;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HookActionTest {

    @Test
    void shellActionExecutesCommand() {
        var action = new ShellAction("echo hello", null, null, 10);
        String output = action.execute(new HookContext(HookEvent.STARTUP, Map.of()));
        assertTrue(output.contains("hello"));
    }

    @Test
    void promptActionReturnsText() {
        var action = new PromptAction("injected text");
        String output = action.execute(new HookContext(HookEvent.STARTUP, Map.of()));
        assertEquals("injected text", output);
    }

    @Test
    void httpActionReturnsStatusAndBody() {
        // Use a known public endpoint
        var action = new HttpAction("https://httpbin.org/get", "GET", null, null, 10);
        String output = action.execute(new HookContext(HookEvent.STARTUP, Map.of()));
        assertTrue(output.contains("200"));
    }

    @Test
    void subAgentActionReturnsPlaceholder() {
        var action = new SubAgentAction();
        String output = action.execute(new HookContext(HookEvent.STARTUP, Map.of()));
        assertTrue(output.contains("not yet implemented"));
    }
}
