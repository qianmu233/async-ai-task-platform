package com.example.aitask.repository;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.messaging.*;
import com.example.aitask.model.MockModelClient;
import com.example.aitask.outbox.*;
import com.example.aitask.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.dao.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL + Rabbit only AFTER human migration approval. No DDL, cleanup, old task/event mutation or HTTP claim API. */
class TaskPipelineOutboxIT {
    private final String run = UUID.randomUUID().toString();
    private final OutboxSettings settings = new OutboxSettings(1, 2, 30000, 100, 1000);
    private final List<Connection> connections = new ArrayList<>();

    @Test
    void atomicEnqueueCasRaceConfirmedReplayAndMandatoryFailure() throws Exception {
        assertTrue(Boolean.getBoolean("phase4a.outbox.enabled"), "Use explicit -Poutbox-it");
        assertEquals("true", System.getenv("OUTBOX_IT_SCHEMA_APPROVED"),
                "REFUSED: reviewed manual 002_outbox migration approval required; no connection or write executed");
        var mysql = MySqlAcceptanceSupport.configuration(true, System.getenv());
        String rabbitHost = required("RABBITMQ_HOST"), rabbitPort = required("RABBITMQ_PORT");
        String rabbitUser = required("RABBITMQ_USERNAME"), rabbitPassword = required("RABBITMQ_PASSWORD");
        assertEquals("127.0.0.1", rabbitHost, "Acceptance requires dedicated local P1 broker");
        assertEquals("5673", rabbitPort, "Acceptance requires dedicated P1 port");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CachingConnectionFactory broker = null;
        SimpleMessageListenerContainer listener = null;
        try {
            for (int i = 0; i < 3; i++) {
                Properties credentials = new Properties();
                credentials.setProperty("user", mysql.username()); credentials.setProperty("password", mysql.password());
                try { connections.add(DriverManager.getConnection(mysql.jdbcUrl(), credentials)); }
                finally { credentials.clear(); }
            }
            var sources = connections.stream().map(c -> new SingleConnectionDataSource(c, true)).toList();
            var managers = sources.stream().map(DataSourceTransactionManager::new).toList();
            CountDownLatch bothRead = new CountDownLatch(2);
            var a = jdbc(sources.get(0), bothRead);
            var b = jdbc(sources.get(1), bothRead);
            var workerJdbc = jdbc(sources.get(2), null);
            var databases = List.of(a, b, workerJdbc);
            for (int index = 0; index < databases.size(); index++) {
                var db = databases.get(index);
                assertEquals("ai_task_platform", db.queryForObject("SELECT DATABASE()", String.class));
                assertTrue(db.queryForObject("SELECT VERSION()", String.class).matches("8\\.0\\.\\d+.*"));
                assertTrue(db.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("ai_task_app@"));
                assertEquals("InnoDB", db.queryForObject("""
                        SELECT ENGINE FROM information_schema.TABLES
                        WHERE TABLE_SCHEMA='ai_task_platform' AND TABLE_NAME='ai_task_outbox'
                        """, String.class));
                new JdbcOutboxRepository(db, managers.get(index), settings).verifySchema(); // SELECT columns only.
            }
            long sessionA = a.queryForObject("SELECT CONNECTION_ID()", Long.class);
            long sessionB = b.queryForObject("SELECT CONNECTION_ID()", Long.class);
            long sessionWorker = workerJdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertNotEquals(sessionA, sessionB);
            assertNotEquals(sessionWorker, sessionA);
            assertNotEquals(sessionWorker, sessionB);
            var tasks = new JdbcTaskRepository(a, managers.get(0), true);
            var workerTasks = new JdbcTaskRepository(workerJdbc, managers.get(2), true);
            var service = new TaskService(tasks, new OutboxSubmissionDispatcher());
            broker = new CachingConnectionFactory(rabbitHost, Integer.parseInt(rabbitPort));
            broker.setUsername(rabbitUser); broker.setPassword(rabbitPassword); broker.setVirtualHost("/ai-task-platform");
            broker.setConnectionTimeout(5000);
            broker.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); broker.setPublisherReturns(true);
            var names = new TaskQueueNames("p1.it.outbox." + run);
            var admin = new RabbitAdmin(broker);
            admin.declareExchange(new DirectExchange(names.exchange(), true, false));
            admin.declareQueue(new org.springframework.amqp.core.Queue(names.work(), true));
            admin.declareQueue(new org.springframework.amqp.core.Queue(names.parking(), true));
            admin.declareBinding(new Binding(names.work(), Binding.DestinationType.QUEUE, names.exchange(), "task", null));
            admin.declareBinding(new Binding(names.parking(), Binding.DestinationType.QUEUE, names.exchange(), "park", null));
            var template = new RabbitTemplate(broker); template.setMandatory(true);
            var publisher = new ConfirmedRabbitPublisher(template, names, 1000);
            AtomicInteger models = new AtomicInteger(), deliveries = new AtomicInteger(), pauses = new AtomicInteger();
            var consumer = new RabbitTaskConsumer(new TaskWorker(workerTasks, input -> {
                models.incrementAndGet(); return new MockModelClient().process(input);
            }), publisher, pauses::incrementAndGet, 2, 100, Thread::sleep);
            listener = new SimpleMessageListenerContainer(broker);
            listener.setQueueNames(names.work()); listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            listener.setPrefetchCount(1); listener.setConcurrentConsumers(1); listener.setDefaultRequeueRejected(false);
            listener.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
                consumer.receive(message, channel); deliveries.incrementAndGet();
            });
            listener.start();
            OutboxDispatcher.Publisher publish = (event, task) -> publisher.publish(
                    MessageBuilder.fromMessage(TaskMessages.task(task)).setMessageId(event.toString()).build(), "task");

            var initial = service.submit("outbox-it:" + run + ":race", "hello outbox");
            UUID first = initial.task().id();
            assertEquals(first, service.submit("outbox-it:" + run + ":race", "hello outbox").task().id());
            assertEquals(1, a.queryForObject("SELECT COUNT(*) FROM ai_task_outbox WHERE task_id=?", Integer.class, first.toString()));
            var repoA = new JdbcOutboxRepository(a, managers.get(0), settings, first);
            var repoB = new JdbcOutboxRepository(b, managers.get(1), settings, first);
            Future<OutboxDispatcher.Result> raceA = pool.submit(() -> new OutboxDispatcher(repoA, publish, settings).dispatchBatch());
            Future<OutboxDispatcher.Result> raceB = pool.submit(() -> new OutboxDispatcher(repoB, publish, settings).dispatchBatch());
            var rA = raceA.get(15, TimeUnit.SECONDS); var rB = raceB.get(15, TimeUnit.SECONDS);
            assertEquals(1, rA.claimed() + rB.claimed()); assertEquals(1, rA.markedSent() + rB.markedSent());
            assertEquals(0, rA.storageFailures() + rB.storageFailures() + rA.unexpectedFailures() + rB.unexpectedFailures());
            await(Duration.ofSeconds(10), () -> deliveries.get() == 1 && tasks.findById(first).orElseThrow().status() == TaskStatus.SUCCESS);
            assertEquals(1, models.get());
            assertEvent(a, first, "SENT", 1, 2);

            // Controlled failure AFTER real confirm, BEFORE SENT update; real DB lease expires naturally.
            UUID replayTask = service.submit("outbox-it:" + run + ":replay", "hello replay").task().id();
            var replay = new JdbcOutboxRepository(a, managers.get(0), settings, replayTask);
            var peerReplay = new JdbcOutboxRepository(b, managers.get(1), settings, replayTask);
            AtomicReference<OutboxRepository.Claim> oldClaim = new AtomicReference<>();
            OutboxRepository interrupted = new OutboxRepository() {
                @Override public Acquisition claim() { var got = replay.claim(); oldClaim.set(got.claim()); return got; }
                @Override public boolean markSent(Claim claim) { throw new DataAccessResourceFailureException("Injected pre-update failure"); }
                @Override public boolean markFailure(Claim claim, String code, long delay, boolean isolate) {
                    throw new AssertionError("Unknown completion must not be rewritten as failed publication");
                }
            };
            var gap = new OutboxDispatcher(interrupted, publish, settings).dispatchBatch();
            assertEquals(1, gap.confirmed()); assertEquals(0, gap.markedSent()); assertEquals(1, gap.storageFailures());
            await(Duration.ofSeconds(10), () -> deliveries.get() == 2 && tasks.findById(replayTask).orElseThrow().status() == TaskStatus.SUCCESS);
            assertEvent(a, replayTask, "DISPATCHING", 1, 1);
            var retryDispatcher = new OutboxDispatcher(replay, (event, task) -> {
                assertFalse(peerReplay.markSent(oldClaim.get()), "Old owner cannot write while a new claim owns the event");
                publish.publish(event, task);
            }, settings);
            await(Duration.ofSeconds(35), () -> retryDispatcher.dispatchBatch().markedSent() == 1);
            await(Duration.ofSeconds(10), () -> deliveries.get() == 3);
            assertEvent(a, replayTask, "SENT", 2, 3);
            assertEquals(2, models.get(), "Replay is delivered but must not repeat successful task claim/model execution");
            assertEquals(2L, a.queryForObject("SELECT version FROM ai_text_task WHERE task_id=?", Long.class, replayTask.toString()));

            UUID unroutable = service.submit("outbox-it:" + run + ":return", "not executed").task().id();
            var bad = new JdbcOutboxRepository(a, managers.get(0), settings, unroutable);
            var failed = new OutboxDispatcher(bad, (event, task) -> publisher.publish(TaskMessages.task(task), "no-such-route"), settings);
            assertEquals(1, failed.dispatchBatch().publicationFailures());
            assertEvent(a, unroutable, "RETRY", 1, 2);
            await(Duration.ofSeconds(3), () -> failed.dispatchBatch().isolated() == 1);
            assertEvent(a, unroutable, "ISOLATED", 2, 4);
            assertEquals(TaskStatus.PENDING, tasks.findById(unroutable).orElseThrow().status());
            assertEquals(2, models.get());

            // Actual MySQL foreign-key rejection rolls back task INSERT; no synthetic database/DDL.
            String rollbackKey = "outbox-it:" + run + ":rollback";
            var rejectingJdbc = new JdbcTemplate(sources.get(0)) {
                @Override public int update(String sql, Object... args) {
                    if (sql.stripLeading().startsWith("INSERT INTO ai_task_outbox")) {
                        return super.update(sql, args[0], UUID.randomUUID().toString());
                    }
                    return super.update(sql, args);
                }
            };
            rejectingJdbc.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator("MySQL"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> new JdbcTaskRepository(rejectingJdbc, managers.get(0), true).createOrGet(rollbackKey, "rollback"));
            assertEquals(0, a.queryForObject("SELECT COUNT(*) FROM ai_text_task WHERE idempotency_key=?", Integer.class, rollbackKey));
            listener.stop();
            assertEquals(0, pauses.get());
            assertEquals(0, admin.getQueueInfo(names.work()).getMessageCount());
            assertEquals(0, admin.getQueueInfo(names.work()).getConsumerCount());
            assertEquals(0, admin.getQueueInfo(names.parking()).getMessageCount());
            System.out.printf("OUTBOX_IT RESULT run=%s dispatchConnectionA=%d dispatchConnectionB=%d workerConnection=%d "
                            + "claimedRace=1 markedSentRace=1 delivered=3 modelCalls=2 replay=true staleOwnerRejected=true "
                            + "mandatoryFailures=2 isolated=1 rollback=true retainedTasks=3 retainedEvents=3%n",
                    run, sessionA, sessionB, sessionWorker);
        } catch (SQLException | RuntimeException failure) {
            throw MySqlAcceptanceSupport.failure("outbox-pipeline", failure);
        } finally {
            pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS);
            if (listener != null) { listener.stop(); listener.destroy(); }
            if (broker != null) { broker.destroy(); }
            for (var connection : connections) { connection.close(); }
        }
    }

    private JdbcTemplate jdbc(SingleConnectionDataSource source, CountDownLatch bothRead) {
        AtomicBoolean first = new AtomicBoolean(true);
        var jdbc = new JdbcTemplate(source) {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                List<T> rows = super.query(sql, mapper, args);
                if (bothRead != null && sql.startsWith("SELECT event_id") && !rows.isEmpty() && first.compareAndSet(true, false)) {
                    bothRead.countDown();
                    try { assertTrue(bothRead.await(5, TimeUnit.SECONDS), "Both real SELECTs must observe the same candidate before CAS"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("Read barrier interrupted"); }
                }
                return rows;
            }
        };
        jdbc.setQueryTimeout(10); jdbc.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator("MySQL"));
        return jdbc;
    }

    private void assertEvent(JdbcTemplate jdbc, UUID task, String status, int attempts, long version) {
        var row = jdbc.queryForMap("SELECT status,attempts,version FROM ai_task_outbox WHERE task_id=?", task.toString());
        assertEquals(status, row.get("status"));
        assertEquals(attempts, ((Number) row.get("attempts")).intValue());
        assertEquals(version, ((Number) row.get("version")).longValue());
    }
    private void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) { return; } Thread.sleep(100); }
        fail("Actual Outbox pipeline timeout; no fallback");
    }
    private String required(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "Missing external " + name);
        return value;
    }
}
