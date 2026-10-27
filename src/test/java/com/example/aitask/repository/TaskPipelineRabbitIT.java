package com.example.aitask.repository;

import com.example.aitask.AiTaskPlatformApplication;
import com.example.aitask.api.TaskController;
import com.example.aitask.domain.TaskStatus;
import com.example.aitask.messaging.*;
import com.example.aitask.model.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit real RabbitMQ + real MySQL + real HTTP. No fixture, broker mock or schema creation. */
class TaskPipelineRabbitIT {
    private static final AtomicInteger modelCalls = new AtomicInteger();
    private static final Map<String, AtomicInteger> callsByInstance = new ConcurrentHashMap<>();
    private static volatile CountDownLatch raceModelGate = new CountDownLatch(0);

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("rabbit-counting-it")
    static class CountingModelConfiguration {
        @Bean @Primary
        ModelClient countedMockModel(Environment environment) {
            MockModelClient actual = new MockModelClient();
            String instance = environment.getProperty("phase3a.instance", "A");
            return input -> {
                modelCalls.incrementAndGet();
                callsByInstance.computeIfAbsent(instance, key -> new AtomicInteger()).incrementAndGet();
                if (input.startsWith("phase3a-race:")) {
                    try {
                        if (!raceModelGate.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("Test model gate timeout"); }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Test model interrupted");
                    }
                }
                return actual.process(input);
            };
        }
    }

    @Test
    void realHttpMysqlAndRabbitPipelineConfirmsConsumesDeduplicatesAndIsolates() {
        assertTrue(Boolean.getBoolean("phase3a.rabbit.enabled"), "Use the explicit rabbit-it Maven profile");
        var mysql = MySqlAcceptanceSupport.configuration(true, System.getenv());
        String rabbitUsername = required("RABBITMQ_USERNAME");
        String rabbitPassword = required("RABBITMQ_PASSWORD");
        String rabbitHost = required("RABBITMQ_HOST");
        String rabbitPort = required("RABBITMQ_PORT");
        String run = UUID.randomUUID().toString();
        var names = new TaskQueueNames("p1.it." + run);
        var properties = new HashMap<String, Object>();
        properties.put("platform.storage.mode", "mysql");
        properties.put("platform.storage.mysql.host", mysql.host());
        properties.put("platform.storage.mysql.port", mysql.port());
        properties.put("platform.storage.mysql.username", mysql.username());
        properties.put("platform.storage.mysql.password", mysql.password());
        properties.put("platform.messaging.mode", "rabbit");
        properties.put("platform.messaging.queue-prefix", names.prefix());
        properties.put("platform.messaging.rabbitmq.host", rabbitHost);
        properties.put("platform.messaging.rabbitmq.port", rabbitPort);
        properties.put("platform.messaging.rabbitmq.username", rabbitUsername);
        properties.put("platform.messaging.rabbitmq.password", rabbitPassword);
        properties.put("platform.messaging.rabbitmq.virtual-host", "/ai-task-platform");
        properties.put("logging.level.root", "OFF");
        properties.put("spring.main.banner-mode", "off");
        properties.put("debug", "false");
        properties.put("server.port", 0);
        ConfigurableApplicationContext context = null;
        ConfigurableApplicationContext peer = null;
        try {
            modelCalls.set(0);
            callsByInstance.clear();
            context = startInstance(properties, "A");
            var repository = context.getBean(TaskRepository.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            assertEquals("ai_task_platform", jdbc.queryForObject("SELECT DATABASE()", String.class));
            String mysqlVersion = jdbc.queryForObject("SELECT VERSION()", String.class);
            assertTrue(mysqlVersion.startsWith("8.0."));
            assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("ai_task_app@"));
            var factory = context.getBean(CachingConnectionFactory.class);
            var admin = context.getBean(RabbitAdmin.class);
            var publisher = context.getBean(ConfirmedRabbitPublisher.class);
            var container = (SimpleMessageListenerContainer) context.getBean(RabbitListenerEndpointRegistry.class)
                    .getListenerContainer(RabbitTaskConsumer.LISTENER_ID);
            AtomicInteger delivered = new AtomicInteger();
            Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();
            observe(container, "A", delivered, seen);
            try (var broker = factory.getRabbitConnectionFactory().newConnection("p1-it-inspection")) {
                assertEquals("RabbitMQ", broker.getServerProperties().get("product").toString());
                System.out.printf("RABBIT_IT PREFLIGHT run=%s mysql=%s rabbit=%s queue=%s%n", run,
                        mysqlVersion, broker.getServerProperties().get("version"), names.work());
                try (var inspection = broker.createChannel()) {
                    // The real broker rejects mismatched durability declarations.
                    inspection.queueDeclare(names.work(), true, false, false, null);
                    inspection.queueDeclare(names.parking(), true, false, false, null);
                    RestClient http = RestClient.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"));
                    String successKey = "phase3a:" + run + ":success";
                    String failureKey = "phase3a:" + run + ":failure";
                    var created = http.post().uri("/api/tasks")
                            .body(new TaskController.SubmitRequest(successKey, "hello phase3a"))
                            .retrieve().toEntity(TaskController.SubmitResponse.class);
                    assertEquals(202, created.getStatusCode().value());
                    UUID successId = created.getBody().taskId();
                    var failed = http.post().uri("/api/tasks")
                            .body(new TaskController.SubmitRequest(failureKey, "[MOCK_FAIL]"))
                            .retrieve().toEntity(TaskController.SubmitResponse.class);
                    assertEquals(202, failed.getStatusCode().value());
                    UUID failureId = failed.getBody().taskId();
                    await(() -> repository.findById(successId).orElseThrow().status() == TaskStatus.SUCCESS
                            && repository.findById(failureId).orElseThrow().status() == TaskStatus.FAILED);
                    var repeated = http.post().uri("/api/tasks")
                            .body(new TaskController.SubmitRequest(successKey, "hello phase3a"))
                            .retrieve().toEntity(TaskController.SubmitResponse.class);
                    assertEquals(200, repeated.getStatusCode().value());
                    assertEquals(successId, repeated.getBody().taskId());
                    for (int duplicate = 0; duplicate < 3; duplicate++) { publisher.dispatch(successId); }
                    publisher.publish(new Message("malformed".getBytes(StandardCharsets.US_ASCII), new MessageProperties()), "task");
                    publisher.dispatch(UUID.randomUUID());
                    assertThrows(ConfirmedRabbitPublisher.PublicationFailure.class,
                            () -> publisher.publish(TaskMessages.task(successId), "no-such-route"));
                    await(() -> delivered.get() == 7 && admin.getQueueInfo(names.parking()).getMessageCount() == 2);
                    assertTrue(container.isRunning(), "ACK/parking failures must not be concealed");
                    assertEquals(2, modelCalls.get(), "Duplicates cannot invoke the model again");
                    assertEquals(0, admin.getQueueInfo(names.work()).getMessageCount());
                    var success = repository.findById(successId).orElseThrow();
                    var failure = repository.findById(failureId).orElseThrow();
                    assertEquals("[MOCK] hello phase3a", success.output());
                    assertNull(success.errorCode());
                    assertNull(failure.output());
                    assertEquals("MODEL_CALL_FAILED", failure.errorCode());
                    assertNotNull(success.completedAt());
                    assertNotNull(failure.completedAt());
                    for (UUID id : new UUID[]{successId, failureId}) {
                        assertEquals(2L, jdbc.queryForObject("SELECT version FROM ai_text_task WHERE task_id=?", Long.class, id.toString()));
                    }
                    assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_text_task WHERE idempotency_key IN (?,?)",
                            Long.class, successKey, failureKey));
                    var firstParked = inspection.basicGet(names.parking(), false);
                    var secondParked = inspection.basicGet(names.parking(), false);
                    assertNotNull(firstParked);
                    assertNotNull(secondParked);
                    var reasons = java.util.Set.of(firstParked.getProps().getHeaders().get("x-isolation-reason").toString(),
                            secondParked.getProps().getHeaders().get("x-isolation-reason").toString());
                    assertEquals(java.util.Set.of("INVALID_MESSAGE", "TASK_NOT_FOUND"), reasons);
                    assertEquals(2, firstParked.getProps().getDeliveryMode());
                    // Inspection only: return the two own parking messages, keeping the isolated evidence.
                    inspection.basicNack(secondParked.getEnvelope().getDeliveryTag(), true, true);

                    // Two independent Spring Boot instances in one JVM: distinct HTTP servers, pools and Rabbit connections.
                    peer = startInstance(properties, "B");
                    var peerRepository = peer.getBean(TaskRepository.class);
                    var peerJdbc = peer.getBean(JdbcTemplate.class);
                    var peerContainer = (SimpleMessageListenerContainer) peer.getBean(RabbitListenerEndpointRegistry.class)
                            .getListenerContainer(RabbitTaskConsumer.LISTENER_ID);
                    AtomicInteger peerDelivered = new AtomicInteger();
                    observe(peerContainer, "B", peerDelivered, seen);
                    assertNotSame(factory, peer.getBean(CachingConnectionFactory.class));
                    assertNotSame(context.getBean(javax.sql.DataSource.class), peer.getBean(javax.sql.DataSource.class));
                    long mysqlA = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                    long mysqlB = peerJdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                    assertNotEquals(mysqlA, mysqlB);
                    String portA = context.getEnvironment().getProperty("local.server.port");
                    String portB = peer.getEnvironment().getProperty("local.server.port");
                    assertNotEquals(portA, portB);
                    RestClient peerHttp = RestClient.create("http://127.0.0.1:" + portB);

                    // Prove that each instance can consume the other instance's HTTP publication.
                    container.stop();
                    await(() -> admin.getQueueInfo(names.work()).getConsumerCount() == 2);
                    UUID crossB = submit(http, "phase3a:" + run + ":cross-b", "cross-to-B");
                    await(() -> repository.findById(crossB).orElseThrow().status() == TaskStatus.SUCCESS && peerDelivered.get() == 1);
                    assertEquals(1, callsByInstance.get("B").get());
                    peerContainer.stop();
                    container.start();
                    await(() -> admin.getQueueInfo(names.work()).getConsumerCount() == 2);
                    UUID crossA = submit(peerHttp, "phase3a:" + run + ":cross-a", "cross-to-A");
                    await(() -> peerRepository.findById(crossA).orElseThrow().status() == TaskStatus.SUCCESS && delivered.get() == 8);
                    assertEquals(3, callsByInstance.get("A").get());

                    peerContainer.start();
                    await(() -> admin.getQueueInfo(names.work()).getConsumerCount() == 4);
                    raceModelGate = new CountDownLatch(1);
                    UUID raced = submit(http, "phase3a:" + run + ":race", "phase3a-race:" + run);
                    await(() -> repository.findById(raced).orElseThrow().status() == TaskStatus.RUNNING);
                    for (int duplicate = 0; duplicate < 7; duplicate++) { publisher.dispatch(raced); }
                    await(() -> seen.containsKey("A:" + raced) && seen.containsKey("B:" + raced)
                            && delivered.get() + peerDelivered.get() == 16);
                    assertEquals(5, modelCalls.get(), "Two-instance race may call the model only once for this task");
                    assertEquals(1L, jdbc.queryForObject("SELECT version FROM ai_text_task WHERE task_id=?", Long.class, raced.toString()));
                    raceModelGate.countDown();
                    await(() -> repository.findById(raced).orElseThrow().status() == TaskStatus.SUCCESS
                            && delivered.get() + peerDelivered.get() == 17);
                    assertEquals(5, modelCalls.get());
                    assertEquals(9, admin.getQueueInfo(names.parking()).getMessageCount());
                    assertTrue(container.isRunning() && peerContainer.isRunning());
                    for (UUID task : new UUID[]{successId, failureId, crossA, crossB, raced}) {
                        assertEquals(repository.findById(task), peerRepository.findById(task));
                        assertEquals(2L, jdbc.queryForObject("SELECT version FROM ai_text_task WHERE task_id=?", Long.class, task.toString()));
                    }
                    System.out.printf("RABBIT_IT INSTANCES run=%s sameJvm=true httpA=%s httpB=%s mysqlConnectionA=%d mysqlConnectionB=%d "
                                    + "deliveredA=%d deliveredB=%d modelCallsA=%d modelCallsB=%d racedTask=%s raceDeliveriesA=%d raceDeliveriesB=%d%n",
                            run, portA, portB, mysqlA, mysqlB, delivered.get(), peerDelivered.get(),
                            callsByInstance.get("A").get(), callsByInstance.get("B").get(), raced,
                            seen.get("A:" + raced).get(), seen.get("B:" + raced).get());
                    System.out.printf("RABBIT_IT RESULT run=%s realDelivered=17 modelCalls=5 terminalDuplicates=3 runningRaceConflicts=7 parked=9 "
                                    + "mandatoryReturnRejected=true retainedRows=5 allVersions=2 successTask=%s failedTask=%s%n",
                            run, successId, failureId);
                }
            }
        } catch (AssertionError failure) {
            if (failure.getCause() != null) { throw MySqlAcceptanceSupport.failure("rabbit-pipeline", failure); }
            throw failure;
        } catch (Exception failure) {
            throw MySqlAcceptanceSupport.failure("rabbit-pipeline", failure);
        } finally {
            raceModelGate.countDown();
            if (peer != null) { peer.close(); }
            if (context != null) { context.close(); }
            properties.clear();
        }
    }

    private static ConfigurableApplicationContext startInstance(Map<String, Object> properties, String instance) {
        Map<String, Object> privateProperties = new HashMap<>(properties);
        privateProperties.put("phase3a.instance", instance);
        SpringApplication application = new SpringApplication(AiTaskPlatformApplication.class, CountingModelConfiguration.class);
        application.setAdditionalProfiles("rabbit-counting-it");
        application.addInitializers(app -> app.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("private-it-environment", privateProperties)));
        return application.run("--debug=false", "--logging.level.root=OFF", "--spring.main.banner-mode=off");
    }

    private static void observe(SimpleMessageListenerContainer container, String instance, AtomicInteger delivered,
                                Map<String, AtomicInteger> seen) {
        var actual = (ChannelAwareMessageListener) container.getMessageListener();
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            try {
                UUID id = TaskMessages.id(message);
                seen.computeIfAbsent(instance + ":" + id, key -> new AtomicInteger()).incrementAndGet();
            } catch (IllegalArgumentException ignored) { /* Invalid bodies still reach the real listener. */ }
            actual.onMessage(message, channel);
            delivered.incrementAndGet();
        });
    }

    private static UUID submit(RestClient http, String key, String input) {
        var response = http.post().uri("/api/tasks").body(new TaskController.SubmitRequest(key, input))
                .retrieve().toEntity(TaskController.SubmitResponse.class);
        assertEquals(202, response.getStatusCode().value());
        return response.getBody().taskId();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("Real Rabbit test refused: missing " + name); }
        return value;
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) { return; }
            Thread.sleep(50);
        }
        fail("Real Rabbit pipeline did not reach the required state within 20 seconds");
    }
}
