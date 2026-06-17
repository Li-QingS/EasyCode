package com.easycode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EditFileToolTest {
    private static final ObjectMapper json = new ObjectMapper();

    @TempDir Path tmp;

    @Test
    void shouldReplaceOnce() throws Exception {
        Path f = tmp.resolve("test.txt");
        Files.writeString(f, "hello world");
        ObjectNode input = json.createObjectNode();
        input.put("path", f.toString());
        input.put("old", "world");
        input.put("new", "Java");
        ToolResult r = new EditFileTool().execute(input);
        assertTrue(r.success());
        assertEquals("hello Java", Files.readString(f));
    }

    @Test
    void shouldFailOnNoMatch() throws Exception {
        Path f = tmp.resolve("test.txt");
        Files.writeString(f, "hello");
        ObjectNode input = json.createObjectNode();
        input.put("path", f.toString());
        input.put("old", "nonexistent");
        input.put("new", "x");
        ToolResult r = new EditFileTool().execute(input);
        assertFalse(r.success());
        assertTrue(r.content().contains("未找到"));
    }

    @Test
    void shouldFailOnMultipleMatches() throws Exception {
        Path f = tmp.resolve("test.txt");
        Files.writeString(f, "aaa bbb aaa");
        ObjectNode input = json.createObjectNode();
        input.put("path", f.toString());
        input.put("old", "aaa");
        input.put("new", "x");
        ToolResult r = new EditFileTool().execute(input);
        assertFalse(r.success());
        assertTrue(r.content().contains("2") || r.content().contains("不唯一"));
    }
}
