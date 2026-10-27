package com.example.aitask.model;

import com.example.aitask.repository.InMemoryTaskRepository;
import com.example.aitask.service.TaskWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static com.example.aitask.model.ModelCallException.Kind.*;
import static com.example.aitask.model.LocalModelHttpServer.Scenario.*;

class OpenAiCompatibleModelClientTest {
    private OpenAiCompatibleModelClient client(LocalModelHttpServer server, long timeout, int bytes) {
        return new OpenAiCompatibleModelClient(new OpenAiModelSettings(server.baseUrl(), server.token(), "local-test",
                100, timeout, bytes), new ObjectMapper());
    }
    @Test void actualHttpSuccessUsesNonStreamingProtocolAndWorkerPersistsResult() throws Exception {
        try (var server = new LocalModelHttpServer()) {
            var repo = new InMemoryTaskRepository(Clock.systemUTC(), 20000);
            var id = repo.createOrGet("http-success", "hello 中文").task().id();
            assertThat(new TaskWorker(repo, client(server, 2000, 4096)).execute(id)).isEqualTo(TaskWorker.Outcome.COMPLETED);
            assertThat(repo.findById(id).orElseThrow().output()).isEqualTo("[LOCAL HTTP] hello 中文");
            assertThat(server.validAuthorization.get()).isTrue();
            assertThat(server.request.get().path("model").asText()).isEqualTo("local-test");
            assertThat(server.request.get().path("stream").booleanValue()).isFalse();
            assertThat(server.request.get().path("messages").get(0).path("role").asText()).isEqualTo("user");
            assertThat(new TaskWorker(repo, client(server, 2000, 4096)).execute(id)).isEqualTo(TaskWorker.Outcome.TERMINAL_DUPLICATE);
            assertThat(server.calls.get()).isEqualTo(1);
        }
    }
    @Test void httpErrorsAreClassifiedAndNeverRetriedOrLeaked() throws Exception {
        try (var server = new LocalModelHttpServer()) {
            var scenarios = new LocalModelHttpServer.Scenario[]{LocalModelHttpServer.Scenario.RATE_LIMITED, SERVER_ERROR, AUTH_ERROR, REDIRECT};
            var kinds = new ModelCallException.Kind[]{ModelCallException.Kind.RATE_LIMITED, UPSTREAM_UNAVAILABLE, AUTH_FAILED, HTTP_ERROR};
            for (int i=0;i<scenarios.length;i++) {
                server.scenario(scenarios[i]);
                var failure = catchThrowableOfType(ModelCallException.class, () -> client(server, 2000, 4096).process("private input"));
                assertThat(failure.kind()).isEqualTo(kinds[i]);
                assertThat(failure.getCause()).isNull();
                assertThat(failure.getMessage()).doesNotContain(server.token(), "private input", "sensitive provider");
            }
            assertThat(server.calls.get()).isEqualTo(scenarios.length);
        }
    }
    @Test void malformedEmptyTruncatedAndOversizedResponsesCannotSucceed() throws Exception {
        try (var server = new LocalModelHttpServer()) {
            for (var scenario : new LocalModelHttpServer.Scenario[]{BAD_JSON, EMPTY, INVALID_SHAPE, TRUNCATED, OVERSIZED}) {
                server.scenario(scenario);
                var failure = catchThrowableOfType(ModelCallException.class, () -> client(server, 2000, 1024).process("x"));
                assertThat(failure.kind()).isEqualTo(scenario == OVERSIZED ? RESPONSE_TOO_LARGE : RESPONSE_INVALID);
            }
            assertThat(server.calls.get()).isEqualTo(5);
        }
    }
    @Test void deadlineIncludesHeadersAndBodyAndWorkerKeepsTimeoutCode() throws Exception {
        for (var scenario : new LocalModelHttpServer.Scenario[]{SLOW_HEADERS, SLOW_BODY}) {
            try (var server = new LocalModelHttpServer()) {
                server.scenario(scenario);
                var repo = new InMemoryTaskRepository();
                var id = repo.createOrGet("timeout-"+scenario, "x").task().id();
                long begin = System.nanoTime();
                assertThat(new TaskWorker(repo, client(server, 300, 4096)).execute(id)).isEqualTo(TaskWorker.Outcome.COMPLETED);
                assertThat(Duration.ofNanos(System.nanoTime()-begin)).isLessThan(Duration.ofSeconds(1));
                assertThat(repo.findById(id).orElseThrow().errorCode()).isEqualTo("MODEL_TIMEOUT");
                assertThat(server.calls.get()).isEqualTo(1);
            }
        }
    }
    @Test void closedPortIsTransportFailureWithoutRetry() throws Exception {
        URI endpoint;
        try (var server = new LocalModelHttpServer()) { endpoint = server.baseUrl(); }
        var client = new OpenAiCompatibleModelClient(new OpenAiModelSettings(endpoint, "test-only", "local-test", 100, 1000, 4096), new ObjectMapper());
        assertThat(catchThrowableOfType(ModelCallException.class, () -> client.process("x")).kind()).isEqualTo(TRANSPORT_FAILED);
    }
    @Test void lateActualHttpResultCannotOverwriteTaskTimeout() throws Exception {
        var now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        var repo = new InMemoryTaskRepository(clock, 1000);
        var id = repo.createOrGet("late-http", "x").task().id();
        try (var server = new LocalModelHttpServer()) {
            server.onRequest = () -> { now.set(now.get().plusSeconds(1)); assertThat(repo.expireRunning(1, id)).isEqualTo(1); };
            assertThat(new TaskWorker(repo, client(server, 2000, 4096)).execute(id)).isEqualTo(TaskWorker.Outcome.TERMINAL_DUPLICATE);
            assertThat(repo.findById(id).orElseThrow().errorCode()).isEqualTo("TASK_TIMEOUT");
            assertThat(server.calls.get()).isEqualTo(1);
        }
    }
}
