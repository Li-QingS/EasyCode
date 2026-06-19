package com.easycode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class StdioTransport implements McpTransport {
    private static final ObjectMapper json = new ObjectMapper();
    private final String command;
    private final java.util.List<String> args;
    private final java.util.Map<String, String> env;
    private Process process;
    private PrintWriter stdin;
    private volatile boolean running;

    StdioTransport(String command, java.util.List<String> args, java.util.Map<String, String> env) {
        this.command = command; this.args = args; this.env = env;
    }

    @Override
    public void start(Consumer<JsonNode> onMessage) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(command);
            if (args != null) cmd.addAll(args);
            pb.command(cmd);
            if (env != null) pb.environment().putAll(env);
            pb.redirectErrorStream(false);
            process = pb.start();
            stdin = new PrintWriter(process.getOutputStream(), true);
            running = true;
            // stdout reader thread
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = r.readLine()) != null) {
                        try { onMessage.accept(json.readTree(line)); }
                        catch (Exception e) { /* skip malformed lines */ }
                    }
                } catch (Exception e) { /* stream closed */ }
            }, "mcp-stdio-reader");
            reader.setDaemon(true);
            reader.start();
            // stderr forwarder thread
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) System.err.println("[mcp-stderr] " + line);
                } catch (Exception e) {}
            }, "mcp-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start stdio transport: " + e.getMessage(), e);
        }
    }

    @Override
    public void send(JsonNode message) {
        if (stdin != null) {
            stdin.println(message.toString());
            stdin.flush();
        }
    }

    @Override
    public void close() {
        running = false;
        if (stdin != null) { stdin.close(); stdin = null; }
        if (process != null) {
            try { process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) {}
            process.destroyForcibly();
            process = null;
        }
    }
}
