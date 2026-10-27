package com.example.aitask.model;

import com.example.aitask.domain.TextTask;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static com.example.aitask.model.ModelCallException.Kind.*;

/** One bounded, non-streaming Chat Completions request per invocation. No redirects or application retries. */
public final class OpenAiCompatibleModelClient implements ModelClient {
    private final OpenAiModelSettings settings;
    private final HttpClient http;
    private final ObjectMapper json;
    public OpenAiCompatibleModelClient(OpenAiModelSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.json = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.connectTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }
    @Override public String process(String input) {
        byte[] body;
        try {
            body = json.writeValueAsBytes(Map.of("model", settings.model(), "stream", false, "n", 1,
                    "messages", List.of(Map.of("role", "user", "content", input))));
        } catch (Exception invalid) { throw new ModelCallException(REQUEST_REJECTED); }
        var request = HttpRequest.newBuilder(settings.endpoint())
                .timeout(Duration.ofMillis(settings.requestTimeoutMillis()))
                .header("Authorization", "Bearer " + settings.apiKey()).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> {
            // Reject status before buffering a potentially sensitive/error response body.
            if (info.statusCode() != 200) { throw statusFailure(info.statusCode()); }
            if (!info.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                throw new ModelCallException(RESPONSE_INVALID);
            }
            return new BoundedBody(settings.maxResponseBytes());
        });
        try {
            // This bounds the entire body, including a peer that sends headers and then stalls.
            byte[] response = future.get(settings.requestTimeoutMillis(), TimeUnit.MILLISECONDS).body();
            return parse(response);
        } catch (TimeoutException timeout) {
            future.cancel(true); throw new ModelCallException(TIMEOUT);
        } catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt(); throw new ModelCallException(INTERRUPTED);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) { cause = cause.getCause(); }
            if (cause instanceof ModelCallException classified) { throw classified; }
            if (cause instanceof HttpTimeoutException) { throw new ModelCallException(TIMEOUT); }
            throw new ModelCallException(TRANSPORT_FAILED);
        }
    }
    private ModelCallException statusFailure(int status) {
        return new ModelCallException(status == 429 ? RATE_LIMITED : status == 401 || status == 403 ? AUTH_FAILED
                : status >= 500 ? UPSTREAM_UNAVAILABLE : status >= 400 ? REQUEST_REJECTED : HTTP_ERROR);
    }
    private String parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode choices = root.path("choices");
            if (!root.isObject() || root.hasNonNull("error") || !choices.isArray() || choices.size() != 1) {
                throw new IllegalArgumentException();
            }
            JsonNode choice = choices.get(0), message = choice.path("message"), content = message.path("content");
            if (!"stop".equals(choice.path("finish_reason").asText()) || !message.isObject()
                    || !"assistant".equals(message.path("role").asText()) || !content.isTextual()
                    || message.hasNonNull("refusal") || message.hasNonNull("tool_calls")) { throw new IllegalArgumentException(); }
            String result = content.textValue();
            TextTask.validateOutput(result);
            return result;
        } catch (Exception invalid) { throw new ModelCallException(RESPONSE_INVALID); }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new ModelCallException(RESPONSE_TOO_LARGE)); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
