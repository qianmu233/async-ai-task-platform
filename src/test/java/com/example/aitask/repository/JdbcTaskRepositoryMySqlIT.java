package com.example.aitask.repository;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.domain.TextTask;
import com.example.aitask.service.TaskException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL only. No Spring context, model, fixture, schema initialization, or cleanup. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class JdbcTaskRepositoryMySqlIT {
    private static final int REPETITIONS = 5;
    private final String runId = UUID.randomUUID().toString();
    private final List<Connection> connections = new ArrayList<>();
    private final List<Worker> workers = new ArrayList<>();
    private int claimSuccesses;
    private int claimConflicts;
    private int completionSuccesses;
    private int completionConflicts;
    private int inputSuccesses;
    private int inputConflicts;
    private int reuseSuccesses;
    private int reuseDuplicates;
    private boolean workersStopped = true;

    private record Worker(long connectionId, JdbcTemplate jdbc, JdbcTaskRepository repository) { }
    private record Outcome<T>(T value, String conflict) { }
    private record Snapshot(TextTask task, long version) { }

    @BeforeAll
    void openTwoIndependentRealConnections() {
        var config = MySqlAcceptanceSupport.configuration(
                Boolean.getBoolean("phase2b.mysql.enabled"), System.getenv());
        checked("connect-and-preflight", () -> {
            String serverUuid = null;
            for (int index = 0; index < 2; index++) {
                Properties credentials = new Properties();
                credentials.setProperty("user", config.username());
                credentials.setProperty("password", config.password());
                Connection connection;
                try {
                    connection = DriverManager.getConnection(config.jdbcUrl(), credentials);
                    connections.add(connection);
                    assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
                    assertEquals("ai_task_platform", connection.getCatalog());
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                } catch (SQLException failure) {
                    throw MySqlAcceptanceSupport.failure("connect", failure);
                } finally {
                    credentials.clear();
                }
                // A separate source/manager per held physical connection. close() is suppressed inside transactions.
                var source = new SingleConnectionDataSource(connection, true);
                var jdbc = new JdbcTemplate(source);
                jdbc.setQueryTimeout(10);
                jdbc.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator("MySQL"));
                String version = jdbc.queryForObject("SELECT VERSION()", String.class);
                assertNotNull(version);
                assertTrue(version.matches("8\\.0\\.\\d+.*"), "Acceptance requires real MySQL 8.0");
                int patch = Integer.parseInt(version.split("\\.")[2].replaceAll("[^0-9].*$", ""));
                assertTrue(patch >= 16, "Acceptance requires enforced CHECK support (8.0.16+)");
                assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("ai_task_app@"),
                        "Acceptance requires the dedicated business account");
                String currentServer = jdbc.queryForObject("SELECT @@server_uuid", String.class);
                if (serverUuid == null) { serverUuid = currentServer; }
                else { assertEquals(serverUuid, currentServer, "Both connections must use the same server"); }
                String sqlMode = jdbc.queryForObject("SELECT @@session.sql_mode", String.class);
                assertEquals("READ-COMMITTED", jdbc.queryForObject(
                        "SELECT @@session.transaction_isolation", String.class));
                assertTrue(sqlMode.contains("STRICT_TRANS_TABLES") || sqlMode.contains("STRICT_ALL_TABLES"),
                        "Strict SQL mode is required");
                assertEquals("InnoDB", jdbc.queryForObject("""
                        SELECT ENGINE FROM information_schema.TABLES
                        WHERE TABLE_SCHEMA = 'ai_task_platform' AND TABLE_NAME = 'ai_text_task'
                        """, String.class), "The existing task table must be InnoDB");
                jdbc.execute("SET SESSION innodb_lock_wait_timeout = 5");
                long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                workers.add(new Worker(id, jdbc,
                        new JdbcTaskRepository(jdbc, new DataSourceTransactionManager(source))));
                System.out.printf("MYSQL_IT PREFLIGHT run=%s worker=%d connectionId=%d mysql=%s isolation=%d%n",
                        runId, index, id, version, Connection.TRANSACTION_READ_COMMITTED);
            }
            assertNotEquals(workers.get(0).connectionId(), workers.get(1).connectionId(),
                    "Workers must use two distinct real server sessions");
        });
    }

    @Order(1)
    @RepeatedTest(REPETITIONS)
    void onlyOneWorkerClaimsPending() {
        checked("claim-race", () -> {
            UUID id = pending("claim");
            var results = race(() -> workers.get(0).repository().start(id),
                    () -> workers.get(1).repository().start(id));
            TextTask winner = stateWinner(results, TaskStatus.PENDING);
            claimSuccesses++;
            claimConflicts++;
            assertEquals(TaskStatus.RUNNING, winner.status());
            Snapshot stored = sameSnapshot(id);
            assertEquals(winner, stored.task());
            assertEquals(1, stored.version());
            assertNotNull(winner.startedAt());
            assertFalse(winner.startedAt().isBefore(winner.createdAt()));
            assertNull(winner.completedAt());
            assertNull(winner.output());
            assertNull(winner.errorCode());
            assertThrows(TaskStateConflictException.class,
                    () -> workers.get(1).repository().fail(id, TaskStatus.PENDING, "EXECUTION_REJECTED"));
            assertEquals(stored, sameSnapshot(id));
            evidence("claim", stored, 1, 1);
        });
    }

    @Order(2)
    @RepeatedTest(REPETITIONS)
    void successAndFailureCompeteForRunningTask() {
        checked("completion-race", () -> {
            UUID id = pending("complete");
            workers.get(0).repository().start(id);
            String output = "phase2b-result-" + id;
            var results = race(() -> workers.get(0).repository().succeed(id, output),
                    () -> workers.get(1).repository().fail(id, TaskStatus.RUNNING, "PHASE2B_FAILURE"));
            TextTask winner = stateWinner(results, TaskStatus.RUNNING);
            completionSuccesses++;
            completionConflicts++;
            Snapshot stored = sameSnapshot(id);
            assertEquals(winner, stored.task());
            assertEquals(2, stored.version());
            assertNotNull(winner.completedAt());
            assertFalse(winner.completedAt().isBefore(winner.startedAt()));
            if (results.get(0).value() != null) {
                assertEquals(TaskStatus.SUCCESS, winner.status());
                assertEquals(output, winner.output());
                assertNull(winner.errorCode());
            } else {
                assertEquals(TaskStatus.FAILED, winner.status());
                assertNull(winner.output());
                assertEquals("PHASE2B_FAILURE", winner.errorCode());
            }
            protectTerminal(stored);
            evidence("complete", stored, 1, 1);
        });
    }

    @Order(3)
    @Test
    void successTerminalCannotBeOverwritten() {
        checked("protect-success", () -> {
            UUID id = pending("protect-success");
            workers.get(0).repository().start(id);
            workers.get(0).repository().succeed(id, "preserved-success");
            Snapshot stored = sameSnapshot(id);
            assertEquals(TaskStatus.SUCCESS, stored.task().status());
            assertEquals(2, stored.version());
            protectTerminal(stored);
            evidence("protect-success", stored, 1, 0);
        });
    }

    @Order(4)
    @Test
    void failedTerminalCannotBeOverwritten() {
        checked("protect-failed", () -> {
            UUID id = pending("protect-failed");
            workers.get(0).repository().start(id);
            workers.get(0).repository().fail(id, TaskStatus.RUNNING, "PRESERVED_FAILURE");
            Snapshot stored = sameSnapshot(id);
            assertEquals(TaskStatus.FAILED, stored.task().status());
            assertEquals(2, stored.version());
            protectTerminal(stored);
            evidence("protect-failed", stored, 1, 0);
        });
    }

    @Order(5)
    @RepeatedTest(REPETITIONS)
    void concurrentSameKeyDifferentInputsHaveOneWinnerAndOneConflict() {
        checked("different-input-race", () -> {
            String key = uniqueKey("different-input");
            assertUnused(key);
            String left = "left-输入-" + runId;
            String right = "right-输入-" + runId;
            var results = race(() -> workers.get(0).repository().createOrGet(key, left),
                    () -> workers.get(1).repository().createOrGet(key, right));
            assertEquals(1, results.stream().filter(result -> result.value() != null).count());
            assertEquals(1, results.stream().filter(result -> "IDEMPOTENCY_CONFLICT".equals(result.conflict())).count());
            int winnerIndex = results.get(0).value() != null ? 0 : 1;
            var registration = results.get(winnerIndex).value();
            assertTrue(registration.created());
            UUID id = registration.task().id();
            Snapshot stored = sameSnapshot(id);
            assertEquals(registration.task(), stored.task());
            assertEquals(winnerIndex == 0 ? left : right, stored.task().input());
            assertEquals(TaskStatus.PENDING, stored.task().status());
            assertEquals(0, stored.version());
            assertSingleRow(key);
            assertFalse(workers.get(1).repository().createOrGet(key, stored.task().input()).created());
            var conflict = assertThrows(TaskException.class,
                    () -> workers.get(0).repository().createOrGet(key, winnerIndex == 0 ? right : left));
            assertEquals(TaskException.Kind.IDEMPOTENCY_CONFLICT, conflict.kind());
            assertEquals(stored, sameSnapshot(id));
            inputSuccesses++;
            inputConflicts++;
            evidence("different-input", stored, 1, 1);
        });
    }

    @Order(6)
    @RepeatedTest(REPETITIONS)
    void concurrentSameKeyEquivalentInputsReuseOneUuid() {
        checked("same-input-race", () -> {
            String key = uniqueKey("same-input");
            assertUnused(key);
            var results = race(() -> workers.get(0).repository().createOrGet(key, "e\u0301\r\n中文"),
                    () -> workers.get(1).repository().createOrGet(key, "é\n中文"));
            assertTrue(results.stream().allMatch(result -> result.conflict() == null));
            assertEquals(1, results.stream().filter(result -> result.value().created()).count());
            UUID id = results.get(0).value().task().id();
            assertEquals(id, results.get(1).value().task().id());
            assertSingleRow(key);
            Snapshot stored = sameSnapshot(id);
            assertEquals("é\n中文", stored.task().input());
            assertEquals(TaskStatus.PENDING, stored.task().status());
            assertEquals(0, stored.version());
            reuseSuccesses++;
            reuseDuplicates++;
            evidence("same-input", stored, 1, 0);
        });
    }

    private UUID pending(String scenario) {
        String key = uniqueKey(scenario);
        assertUnused(key);
        var registration = workers.get(0).repository().createOrGet(key, "phase2b-输入-" + runId);
        assertTrue(registration.created(), "Only a newly inserted test-owned row may be mutated");
        assertSingleRow(key);
        Snapshot stored = sameSnapshot(registration.task().id());
        assertEquals(TaskStatus.PENDING, stored.task().status());
        assertEquals(0, stored.version());
        return registration.task().id();
    }

    private String uniqueKey(String scenario) {
        return "phase2b:" + runId + ":" + scenario + ":" + UUID.randomUUID();
    }

    private void assertUnused(String key) {
        assertEquals(0L, workers.get(0).jdbc().queryForObject(
                "SELECT COUNT(*) FROM ai_text_task WHERE idempotency_key = ?", Long.class, key));
    }

    private void assertSingleRow(String key) {
        for (Worker worker : workers) {
            assertEquals(1L, worker.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM ai_text_task WHERE idempotency_key = ?", Long.class, key));
        }
    }

    private Snapshot sameSnapshot(UUID id) {
        Snapshot left = snapshot(workers.get(0), id);
        assertEquals(left, snapshot(workers.get(1), id));
        return left;
    }

    private Snapshot snapshot(Worker worker, UUID id) {
        // Repository opens a fresh READ_COMMITTED transaction; version is inspected directly, never inferred.
        TextTask task = worker.repository().findById(id).orElseThrow();
        long version = worker.jdbc().queryForObject(
                "SELECT version FROM ai_text_task WHERE task_id = ?", Long.class, id.toString());
        return new Snapshot(task, version);
    }

    private void protectTerminal(Snapshot stored) {
        UUID id = stored.task().id();
        for (Worker worker : workers) {
            assertThrows(TaskStateConflictException.class, () -> worker.repository().start(id));
            assertThrows(TaskStateConflictException.class, () -> worker.repository().succeed(id, "illegal-overwrite"));
            assertThrows(TaskStateConflictException.class,
                    () -> worker.repository().fail(id, TaskStatus.RUNNING, "ILLEGAL_OVERWRITE"));
            assertThrows(TaskStateConflictException.class,
                    () -> worker.repository().fail(id, TaskStatus.PENDING, "ILLEGAL_OVERWRITE"));
            assertThrows(IllegalStateException.class,
                    () -> worker.repository().fail(id, stored.task().status(), "ILLEGAL_OVERWRITE"));
            assertEquals(stored, sameSnapshot(id), "The entire terminal row snapshot must remain unchanged");
        }
    }

    private TextTask stateWinner(List<Outcome<TextTask>> results, TaskStatus expected) {
        assertEquals(1, results.stream().filter(result -> result.value() != null).count());
        assertEquals(1, results.stream().filter(result -> "STATE_CONFLICT".equals(result.conflict())).count());
        var conflict = results.stream().filter(result -> result.conflict() != null).findFirst().orElseThrow();
        assertNull(conflict.value());
        System.out.printf("MYSQL_IT CONFLICT run=%s expected=%s category=STATE_CONFLICT%n", runId, expected);
        return results.stream().filter(result -> result.value() != null).findFirst().orElseThrow().value();
    }

    private <T> List<Outcome<T>> race(Supplier<T> left, Supplier<T> right) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Supplier<T> action : List.of(left, right)) {
                int workerIndex = futures.size();
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new TimeoutException(); }
                    try {
                        return new Outcome<>(action.get(), null);
                    } catch (TaskStateConflictException conflict) {
                        System.out.printf("MYSQL_IT STATE_CONFLICT run=%s connectionId=%d detail=%s%n",
                                runId, workers.get(workerIndex).connectionId(), conflict.getMessage());
                        return new Outcome<T>(null, "STATE_CONFLICT");
                    } catch (TaskException conflict) {
                        if (conflict.kind() != TaskException.Kind.IDEMPOTENCY_CONFLICT) { throw conflict; }
                        return new Outcome<T>(null, "IDEMPOTENCY_CONFLICT");
                    } catch (RuntimeException failure) {
                        throw MySqlAcceptanceSupport.failure("worker", failure);
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both workers must reach the barrier");
            start.countDown();
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) { results.add(future.get(30, TimeUnit.SECONDS)); }
            return results;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof AssertionError safeFailure) { throw safeFailure; }
            throw MySqlAcceptanceSupport.failure("race", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw MySqlAcceptanceSupport.failure("race", failure);
        } catch (TimeoutException failure) {
            throw MySqlAcceptanceSupport.failure("race", failure);
        } finally {
            start.countDown();
            pool.shutdownNow();
            workersStopped = false;
            try {
                workersStopped = pool.awaitTermination(20, TimeUnit.SECONDS);
                assertTrue(workersStopped, "Workers must stop before reusing connections");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw MySqlAcceptanceSupport.failure("stop-workers", failure);
            }
        }
    }

    private void checked(String operation, Runnable action) {
        assertTrue(workersStopped, "Previous workers did not stop; further database operations are refused");
        try {
            action.run();
        } catch (RuntimeException failure) {
            throw MySqlAcceptanceSupport.failure(operation, failure);
        } catch (AssertionError failure) {
            // assertThrows can attach an unexpected vendor exception as its cause.
            if (failure.getCause() != null) { throw MySqlAcceptanceSupport.failure(operation, failure); }
            throw failure;
        }
    }

    private void evidence(String scenario, Snapshot stored, int successes, int conflicts) {
        TextTask task = stored.task();
        System.out.printf("MYSQL_IT ROW run=%s scenario=%s task=%s key=%s status=%s version=%d "
                        + "successes=%d conflicts=%d output=%s error=%s created=%s started=%s completed=%s%n",
                runId, scenario, task.id(), task.idempotencyKey(), task.status(), stored.version(),
                successes, conflicts, task.output(), task.errorCode(), task.createdAt(), task.startedAt(), task.completedAt());
    }

    @AfterAll
    void closeConnectionsWithoutCleaningRows() {
        System.out.printf("MYSQL_IT SUMMARY run=%s realConnections=%d claimSuccesses=%d claimConflicts=%d "
                        + "completionSuccesses=%d completionConflicts=%d inputSuccesses=%d inputConflicts=%d "
                        + "sameInputCreated=%d sameInputReused=%d retainedRowsExpectedOnFullPass=22%n",
                runId, connections.size(), claimSuccesses, claimConflicts, completionSuccesses, completionConflicts,
                inputSuccesses, inputConflicts, reuseSuccesses, reuseDuplicates);
        AssertionError closeFailure = null;
        for (Connection connection : connections) {
            try { connection.close(); }
            catch (SQLException failure) { closeFailure = MySqlAcceptanceSupport.failure("close", failure); }
        }
        if (closeFailure != null) { throw closeFailure; }
    }
}
