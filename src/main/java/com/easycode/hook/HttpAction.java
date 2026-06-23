package com.easycode.hook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** HTTP 请求动作 */
public record HttpAction(
    String url,
    String method,
    Map<String, String> headers,
    String body,
    long timeoutSec
) implements HookAction {

    public HttpAction {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        if (method == null || method.isBlank()) method = "GET";
        if (timeoutSec <= 0) timeoutSec = 30;
    }

    @Override
    public String type() { return "http"; }

    @Override
    public String execute(HookContext ctx) {
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec));
            if (headers != null) headers.forEach(builder::header);
            var bodyPub = (body != null && !body.isEmpty())
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();
            builder.method(method.toUpperCase(), bodyPub);
            var resp = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String respBody = resp.body();
            return resp.statusCode() + "\n" + (respBody.length() > 2000 ? respBody.substring(0, 2000) + "\n...(truncated)" : respBody);
        } catch (Exception e) {
            return "[http error: " + e.getMessage() + "]";
        }
    }
}
