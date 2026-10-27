package com.example.aitask.model;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only actual loopback HTTP, never a third-party model. Captures only synthetic test requests. */
public final class LocalModelHttpServer implements AutoCloseable {
    public enum Scenario { SUCCESS, RATE_LIMITED, SERVER_ERROR, AUTH_ERROR, BAD_JSON, EMPTY, INVALID_SHAPE,
        TRUNCATED, OVERSIZED, SLOW_HEADERS, SLOW_BODY, REDIRECT }
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper json = new ObjectMapper();
    private final String token = "test-only-" + UUID.randomUUID();
    private volatile Scenario scenario = Scenario.SUCCESS;
    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicReference<JsonNode> request = new AtomicReference<>();
    public final AtomicBoolean validAuthorization = new AtomicBoolean();
    public volatile Runnable onRequest = () -> {};
    public LocalModelHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor); server.createContext("/v1/chat/completions", this::respond); server.start();
    }
    public URI baseUrl() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); }
    public String token() { return token; }
    public void scenario(Scenario scenario) { this.scenario = scenario; }
    private void respond(HttpExchange exchange) throws IOException {
        try {
            calls.incrementAndGet();
            validAuthorization.set(("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization")));
            if (!exchange.getRequestMethod().equals("POST") || !validAuthorization.get()) {
                exchange.sendResponseHeaders(401, -1); return;
            }
            request.set(json.readTree(exchange.getRequestBody())); onRequest.run();
            Scenario current = scenario;
            if (current == Scenario.SLOW_HEADERS) { Thread.sleep(1500); }
            int status = switch (current) {
                case RATE_LIMITED -> 429; case SERVER_ERROR -> 503; case AUTH_ERROR -> 401; case REDIRECT -> 302; default -> 200;
            };
            String content = current == Scenario.EMPTY ? " " : "[LOCAL HTTP] " + request.get().path("messages").get(0).path("content").asText();
            String result = json.writeValueAsString(Map.of("choices", List.of(Map.of("index", 0,
                    "finish_reason", current == Scenario.TRUNCATED ? "length" : "stop",
                    "message", Map.of("role", "assistant", "content", content)))));
            if (current == Scenario.BAD_JSON) { result = "not JSON"; }
            if (current == Scenario.INVALID_SHAPE) { result = "{\"choices\":[]}"; }
            if (current == Scenario.OVERSIZED) { result = "x".repeat(4096); }
            if (status != 200) { result = "sensitive provider diagnostic must never be retained"; }
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (current == Scenario.REDIRECT) { exchange.getResponseHeaders().set("Location", baseUrl() + "/chat/completions"); }
            exchange.sendResponseHeaders(status, current == Scenario.SLOW_BODY ? 0 : bytes.length);
            if (current == Scenario.SLOW_BODY) {
                exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); Thread.sleep(1500);
            }
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (IOException disconnected) { /* Expected when bounded client cancels timed-out/oversized response. */ }
        finally { exchange.close(); }
    }
    @Override public void close() {
        server.stop(0); executor.shutdownNow();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
