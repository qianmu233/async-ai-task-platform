package com.example.aitask.ratelimit;

import com.example.aitask.AiTaskPlatformApplication;
import com.example.aitask.api.*;
import com.example.aitask.model.MockModelClient;
import com.example.aitask.repository.InMemoryTaskRepository;
import com.example.aitask.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubmissionRateLimitTest {
    private final List<Runnable> queue = new ArrayList<>();
    private final TaskService service = new TaskService(new InMemoryTaskRepository(), new MockModelClient(), queue::add);
    private final String body = "{\"idempotencyKey\":\"limit-test\",\"input\":\"hello\"}";

    private MockMvc mvc(SubmissionRateLimiter limiter) {
        return MockMvcBuilders.standaloneSetup(new TaskController(service)).setControllerAdvice(new ApiExceptionHandler())
                .addInterceptors(new SubmissionRateLimitInterceptor(limiter)).build();
    }

    @Test
    void admissionUsesTransportPeerAndIgnoresClientIdentityHeaders() throws Exception {
        var source = new AtomicReference<String>();
        mvc(peer -> { source.set(peer); return new SubmissionRateLimiter.Decision(true, 0); })
                .perform(post("/api/tasks").with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
                        .header("Forwarded", "for=198.51.100.1").header("X-Forwarded-For", "198.51.100.2")
                        .header("X-Client-Id", "someone-else").contentType("application/json").content(body))
                .andExpect(status().isAccepted());
        assertThat(source.get()).isEqualTo("192.0.2.10");
        assertThat(queue).hasSize(1);
    }

    @Test
    void limitReturnsStable429BeforeRegistrationAndDoesNotLimitReads() throws Exception {
        var existing = service.submit("existing", "hello").task().id();
        queue.clear();
        var mvc = mvc(peer -> new SubmissionRateLimiter.Decision(false, 7));
        mvc.perform(post("/api/tasks").contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        mvc.perform(get("/api/tasks/{id}", existing)).andExpect(status().isOk());
        assertThat(queue).isEmpty();
        assertThat(service.submit("limit-test", "hello").created()).isTrue();
    }

    @Test
    void redisConnectionFailureIs503WithoutLeakingDetailsOrCreatingTask() throws Exception {
        var brokenRedis = new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new DataAccessResourceFailureException("private connection details");
            }
        };
        mvc(new RedisSubmissionRateLimiter(brokenRedis, "p1:test", 2, 1000))
                .perform(post("/api/tasks").contentType("application/json").content(body))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        assertThat(queue).isEmpty();
        assertThat(service.submit("limit-test", "hello").created()).isTrue();
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(AiTaskPlatformApplication.class)
                .withPropertyValues("debug=false", "platform.execution.workers=1", "platform.execution.queue-capacity=1");
    }

    @Test
    void defaultModeDoesNotCreateRedisConnections() {
        context().run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(RedisConnectionFactory.class)
                .doesNotHaveBean(SubmissionRateLimiter.class));
    }

    @Test
    void enabledModeRefusesMissingCredentialsAndUntrustedForwardingConfiguration() {
        context().withPropertyValues("platform.rate-limit.enabled=true").run(ctx ->
                assertThat(ctx).hasFailed().getFailure().hasMessageContaining("external password"));
        context().withPropertyValues("platform.rate-limit.enabled=true", "platform.rate-limit.redis.password=offline",
                "server.forward-headers-strategy=FRAMEWORK").run(ctx ->
                assertThat(ctx).hasFailed().getFailure().hasMessageContaining("forward-headers-strategy=NONE"));
    }

    @Test
    void invalidLimitsAreRefusedAndIpKeysAvoidRawAddressStorage() {
        assertThatThrownBy(() -> new RedisSubmissionRateLimiter(new StringRedisTemplate(), "other:project", 1, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisSubmissionRateLimiter(new StringRedisTemplate(), "p1:test", 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        var limiter = new RedisSubmissionRateLimiter(new StringRedisTemplate(), "p1:test", 2, 1000);
        assertThat(limiter.keyFor("192.0.2.10")).startsWith("p1:test:").doesNotContain("192.0.2.10");
        assertThat(limiter.keyFor("192.0.2.10")).isNotEqualTo(limiter.keyFor("192.0.2.11"));
    }
}
