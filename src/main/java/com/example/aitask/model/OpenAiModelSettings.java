package com.example.aitask.model;

import java.net.URI;

/** Operator-owned endpoint, never request-controlled. toString deliberately redacts all settings. */
public record OpenAiModelSettings(URI baseUrl, String apiKey, String model,
                                  long connectTimeoutMillis, long requestTimeoutMillis, int maxResponseBytes) {
    public OpenAiModelSettings {
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || (!"https".equals(baseUrl.getScheme()) && !("http".equals(baseUrl.getScheme())
                && java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(baseUrl.getHost())))
                || !baseUrl.equals(baseUrl.normalize())) {
            throw new IllegalArgumentException("Model base URL requires HTTPS or local loopback HTTP, without credentials/query/fragment");
        }
        if (apiKey == null || !apiKey.matches("[!-~]{1,4096}")) {
            throw new IllegalArgumentException("Model requires an external API key without whitespace/control characters");
        }
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw new IllegalArgumentException("Model identifier must be explicit and valid");
        }
        if (connectTimeoutMillis < 100 || connectTimeoutMillis > requestTimeoutMillis
                || requestTimeoutMillis < 100 || requestTimeoutMillis > 120000
                || maxResponseBytes < 256 || maxResponseBytes > 1048576) {
            throw new IllegalArgumentException("Model requires connect <= request timeout (100-120000ms) and response limit 256-1048576 bytes");
        }
    }
    public static URI parseBaseUrl(String value) {
        try { return URI.create(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid model base URL; value redacted"); }
    }
    public URI endpoint() { return URI.create(baseUrl.toString().replaceAll("/+$", "") + "/chat/completions"); }
    @Override public String toString() { return "OpenAI-compatible settings [redacted]"; }
}
