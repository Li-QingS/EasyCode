package com.easycode.hook;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionMatchTest {

    @Test
    void equalsMatch() {
        var c = new ConditionNode.Equals("name", "hello");
        assertTrue(ConditionNode.matches(c, Map.of("name", "hello")));
        assertFalse(ConditionNode.matches(c, Map.of("name", "world")));
    }

    @Test
    void notEqualsMatch() {
        var c = new ConditionNode.NotEquals("name", "hello");
        assertTrue(ConditionNode.matches(c, Map.of("name", "world")));
        assertFalse(ConditionNode.matches(c, Map.of("name", "hello")));
    }

    @Test
    void regexMatch() {
        var c = new ConditionNode.Regex("command", "rm\\s+-rf");
        assertTrue(ConditionNode.matches(c, Map.of("command", "rm -rf /")));
        assertFalse(ConditionNode.matches(c, Map.of("command", "ls -la")));
    }

    @Test
    void globMatch() {
        var c = new ConditionNode.Glob("path", "*.java");
        assertTrue(ConditionNode.matches(c, Map.of("path", "Main.java")));
        assertFalse(ConditionNode.matches(c, Map.of("path", "README.md")));
    }

    @Test
    void allConditionsMet() {
        var c = new ConditionNode.All(List.of(
            new ConditionNode.Equals("name", "exec_command"),
            new ConditionNode.Regex("command", "rm")
        ));
        assertTrue(ConditionNode.matches(c, Map.of("name", "exec_command", "command", "rm file")));
        assertFalse(ConditionNode.matches(c, Map.of("name", "read_file", "command", "rm file")));
    }

    @Test
    void anyConditionMet() {
        var c = new ConditionNode.Any(List.of(
            new ConditionNode.Equals("name", "exec_command"),
            new ConditionNode.Equals("name", "write_file")
        ));
        assertTrue(ConditionNode.matches(c, Map.of("name", "exec_command")));
        assertTrue(ConditionNode.matches(c, Map.of("name", "write_file")));
        assertFalse(ConditionNode.matches(c, Map.of("name", "read_file")));
    }

    @Test
    void nullConditionPasses() {
        assertTrue(ConditionNode.matches(null, Map.of()));
    }
}
