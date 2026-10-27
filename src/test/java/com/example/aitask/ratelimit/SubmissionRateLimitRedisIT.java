package com.example.aitask.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit real Redis acceptance. The HTTP servers are separate OS processes, not Spring contexts in this JVM. */
class SubmissionRateLimitRedisIT {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<Process> children = new ArrayList<>();
    private final String run = UUID.randomUUID().toString();
    private final String namespace = "p1:it:submit:" + run;
    private Path output;

    @Test
    void twoIndependentJvmsShareAtomicQuotaAndRedisFaultFailsClosed() throws Exception {
        require(Boolean.getBoolean("phase3b.redis.enabled"), "Explicit -Predis-it required");
        String host = external("REDIS_HOST");
        int redisPort = Integer.parseInt(external("REDIS_PORT"));
        String password = external("REDIS_PASSWORD");
        require(host.equals("127.0.0.1") && redisPort == 6380,
                "Acceptance only permits the dedicated P1 Redis endpoint 127.0.0.1:6380");
        Path jar = Path.of(System.getProperty("phase3b.app.jar", "")).toAbsolutePath();
        require(Files.isRegularFile(jar), "Packaged P1 application JAR required; run full verify");
        output = Files.createDirectories(Path.of(System.getProperty("phase3b.output")).resolve("redis-processes"));
        var server = new RedisStandaloneConfiguration(host, redisPort);
        server.setUsername(System.getenv().getOrDefault("REDIS_USERNAME", "default"));
        server.setPassword(password);
        var factory = new LettuceConnectionFactory(server, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofMillis(100)).build());
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            factory.afterPropertiesSet();
            factory.start();
            var redis = new StringRedisTemplate(factory);
            String version;
            try (var connection = factory.getConnection()) {
                assertThat(connection.ping()).isEqualTo("PONG");
                version = connection.serverCommands().info("server").getProperty("redis_version");
            }
            require(version != null, "No actual Redis server version returned");
            int portA = availablePort(), portB = availablePort();
            while (portB == portA) { portB = availablePort(); }
            Process a = start(jar, "A", portA, redisPort);
            Process b = start(jar, "B", portB, redisPort);
            assertThat(a.pid()).isNotEqualTo(b.pid()).isNotEqualTo(ProcessHandle.current().pid());
            awaitReady(a, portA);
            awaitReady(b, portB);
            var firstA = submit(portA, 0);
            var firstB = submit(portB, 1);
            assertThat(firstA.statusCode()).isEqualTo(202);
            assertThat(firstB.statusCode()).isEqualTo(202);
            var limiter = new RedisSubmissionRateLimiter(redis, namespace, 4, 60000);
            String key = limiter.keyFor("127.0.0.1");
            long ttlBefore = Objects.requireNonNull(redis.getExpire(key, TimeUnit.MILLISECONDS));
            CountDownLatch release = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> races = new ArrayList<>();
            for (int i = 2; i < 12; i++) {
                int index = i, port = i % 2 == 0 ? portA : portB;
                races.add(pool.submit(() -> { release.await(); return submit(port, index); }));
            }
            release.countDown();
            int admitted = 2, rejected = 0;
            for (var future : races) {
                var response = future.get(15, TimeUnit.SECONDS);
                if (response.statusCode() == 202) { admitted++; }
                else {
                    assertThat(response.statusCode()).isEqualTo(429);
                    assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
                    long retry = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
                    assertThat(retry).isBetween(1L, 60L);
                    rejected++;
                }
            }
            assertThat(admitted).isEqualTo(4);
            assertThat(rejected).isEqualTo(8);
            assertThat(redis.opsForValue().get(key)).isEqualTo("4");
            long ttlAfter = Objects.requireNonNull(redis.getExpire(key, TimeUnit.MILLISECONDS));
            assertThat(ttlAfter).isBetween(1L, ttlBefore);
            JsonNode first = json.readTree(firstA.body());
            var read = get(portA, "/api/tasks/" + first.path("taskId").asText());
            assertThat(read.statusCode()).isEqualTo(200); // Reads remain available at exhausted quota.

            // Natural TTL reset with the same production Lua script, no DEL/FLUSH or clock substitute.
            var shortWindow = new RedisSubmissionRateLimiter(redis, namespace + ":expiry", 1, 200);
            assertThat(shortWindow.acquire("127.0.0.1").allowed()).isTrue();
            assertThat(shortWindow.acquire("127.0.0.1").allowed()).isFalse();
            Thread.sleep(250);
            assertThat(shortWindow.acquire("127.0.0.1").allowed()).isTrue();

            // A third temporary JVM targets an unused local port. Do not stop or alter the real Redis server.
            int faultPort = availablePort(), absentRedisPort = availablePort();
            while (absentRedisPort == faultPort || absentRedisPort == redisPort) { absentRedisPort = availablePort(); }
            Process fault = start(jar, "fault", faultPort, absentRedisPort);
            awaitReady(fault, faultPort);
            var unavailable = submit(faultPort, 100);
            assertThat(unavailable.statusCode()).isEqualTo(503);
            assertThat(json.readTree(unavailable.body()).path("code").asText()).isEqualTo("RATE_LIMIT_UNAVAILABLE");
            assertThat(json.readTree(unavailable.body()).has("taskId")).isFalse();
            assertThat(redis.opsForValue().get(key)).isEqualTo("4");
            System.out.printf("REDIS_IT RESULT run=%s redis=%s pidA=%d pidB=%d httpA=%d httpB=%d crossJvm=true "
                            + "admitted=%d rejected=%d counter=4 ttlMs=%d spoofedHeadersIgnored=true "
                            + "readsAllowed=true expiryReset=true unavailableHttp=503 faultPid=%d%n",
                    run, version, a.pid(), b.pid(), portA, portB, admitted, rejected, ttlAfter, fault.pid());
        } finally {
            pool.shutdownNow();
            for (Process child : children) {
                child.destroy();
                if (!child.waitFor(10, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    require(child.waitFor(5, TimeUnit.SECONDS), "Own test JVM could not be stopped");
                }
            }
            factory.destroy();
        }
    }

    private Process start(Path jar, String name, int port, int redisPort) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-jar", jar.toString(), "--debug=false", "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                "--spring.config.location=classpath:/application.yml");
        Map<String, String> env = builder.environment();
        // Exclude unrelated credentials and overrides from the child application environment.
        env.keySet().removeIf(key -> key.startsWith("TASK_") || key.startsWith("AI_TASK_") || key.startsWith("MYSQL_IT_")
                || key.startsWith("RABBITMQ_") || key.startsWith("SPRING_") || key.startsWith("REDIS_")
                || key.equals("JAVA_TOOL_OPTIONS") || key.equals("JDK_JAVA_OPTIONS") || key.equals("DEBUG"));
        env.put("SERVER_ADDRESS", "127.0.0.1");
        env.put("SERVER_PORT", Integer.toString(port));
        env.put("TASK_STORAGE_MODE", "memory");
        env.put("TASK_MESSAGING_MODE", "local");
        env.put("TASK_RATE_LIMIT_ENABLED", "true");
        env.put("TASK_RATE_LIMIT_NAMESPACE", namespace);
        env.put("TASK_RATE_LIMIT_MAX_REQUESTS", "4");
        env.put("TASK_RATE_LIMIT_WINDOW_MS", "60000");
        env.put("REDIS_HOST", "127.0.0.1");
        env.put("REDIS_PORT", Integer.toString(redisPort));
        env.put("REDIS_USERNAME", System.getenv().getOrDefault("REDIS_USERNAME", "default"));
        env.put("REDIS_PASSWORD", external("REDIS_PASSWORD"));
        env.put("REDIS_TIMEOUT_MS", "1000");
        builder.redirectErrorStream(true).redirectOutput(output.resolve(name + ".log").toFile());
        Process child = builder.start();
        children.add(child);
        return child;
    }

    private void awaitReady(Process child, int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            require(child.isAlive(), "Test application exited; inspect the local child log");
            try {
                if (get(port, "/api/tasks/" + UUID.randomUUID()).statusCode() == 404) { return; }
            } catch (java.io.IOException ignored) { /* readiness only, not an accepted Redis failure */ }
            Thread.sleep(100);
        }
        throw new AssertionError("Test HTTP server failed to become ready");
    }

    private HttpResponse<String> submit(int port, int index) throws Exception {
        String body = json.writeValueAsString(Map.of("idempotencyKey", "redis-it-" + run + "-" + index, "input", "hello"));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tasks"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-Forwarded-For", "198.51.100." + (index + 1))
                .header("Forwarded", "for=203.0.113." + (index + 1)).header("X-Client-Id", "untrusted-" + index)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int availablePort() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }

    private static String external(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "Missing external " + name + "; no real integration executed");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
