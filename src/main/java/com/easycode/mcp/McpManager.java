package com.easycode.mcp;

import com.easycode.tool.Tool;
import com.easycode.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class McpManager implements AutoCloseable {
    private static final int TIMEOUT_SEC = 30;
    private final List<McpClient> clients = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();

    private McpManager() {}

    public static McpManager discoverAndRegister(ToolRegistry registry, Map<String, McpServerConfig> configs) {
        McpManager mgr = new McpManager();
        if (configs.isEmpty()) return mgr;

        ExecutorService executor = Executors.newCachedThreadPool();
        List<Future<?>> futures = new ArrayList<>();

        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            McpServerConfig cfg = entry.getValue();
            futures.add(executor.submit(() -> {
                try {
                    McpTransport transport = createTransport(cfg);
                    McpClient client = new McpClient(transport, name);
                    client.initialize().get(TIMEOUT_SEC, TimeUnit.SECONDS);
                    List<McpClient.ToolDef> toolDefs = client.listTools().get(TIMEOUT_SEC, TimeUnit.SECONDS);
                    synchronized (mgr.clients) { mgr.clients.add(client); }
                    for (McpClient.ToolDef td : toolDefs) {
                        String fullName = "mcp__" + name + "__" + td.name();
                        if (!McpToolAdapter.isValidName(fullName)) {
                            System.err.println("[mcp] warn: skip tool " + fullName + ": invalid name");
                            continue;
                        }
                        McpToolAdapter adapter = new McpToolAdapter(name, td.name(), td.description(),
                            td.inputSchema(), td.readOnlyHint(), client);
                        synchronized (mgr.tools) { mgr.tools.add(adapter); }
                    }
                    System.err.println("[mcp] server " + name + ": connected, " + toolDefs.size() + " tools");
                } catch (TimeoutException e) {
                    System.err.println("[mcp] warn: connect server " + name + " timed out (" + TIMEOUT_SEC + "s)");
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof TimeoutException) {
                        System.err.println("[mcp] warn: connect server " + name + " timed out (" + TIMEOUT_SEC + "s)");
                    } else {
                        String msg = e.getMessage() != null ? e.getMessage() : (cause != null ? cause.toString() : e.getClass().getName());
                        System.err.println("[mcp] warn: connect server " + name + " failed: " + msg);
                    }
                }
            }));
        }

        // Wait for all connections with individual timeout
        for (Future<?> f : futures) {
            try { f.get(TIMEOUT_SEC, TimeUnit.SECONDS); }
            catch (TimeoutException e) { f.cancel(true); }
            catch (Exception e) { /* already logged */ }
        }
        executor.shutdownNow();

        // Register all discovered tools
        synchronized (mgr.tools) {
            for (Tool t : mgr.tools) registry.register(t);
        }
        return mgr;
    }

    private static McpTransport createTransport(McpServerConfig cfg) {
        if ("stdio".equals(cfg.type())) {
            return new StdioTransport(cfg.command(), cfg.args(), cfg.env());
        } else {
            return new HttpTransport(cfg.url(), cfg.headers());
        }
    }

    @Override
    public void close() {
        if (clients.isEmpty()) return;
        CountDownLatch done = new CountDownLatch(clients.size());
        ExecutorService closer = Executors.newCachedThreadPool();
        for (McpClient c : clients) {
            closer.submit(() -> {
                try { c.close(); } catch (Exception e) { /* ignore */ }
                done.countDown();
            });
        }
        try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        closer.shutdownNow();
    }

    public List<Tool> tools() { return List.copyOf(tools); }
}
