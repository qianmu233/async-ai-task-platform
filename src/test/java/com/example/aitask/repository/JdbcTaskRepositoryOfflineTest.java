package com.example.aitask.repository;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.model.MockModelClient;
import com.example.aitask.service.TaskException;
import com.example.aitask.service.TaskInputRules;
import com.example.aitask.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

/** Offline repository contract tests only; this is not real MySQL concurrency verification. */
class JdbcTaskRepositoryOfflineTest {
    private final OfflineJdbcFixture fixture = new OfflineJdbcFixture();
    private final JdbcTaskRepository repository = fixture.repository();

    @Test
    void persistsAndMapsUtcMicrosecondsAndNormalizedInput() {
        var task = repository.createOrGet("persist", "e\u0301\r\ntext").task();
        assertEquals("é\ntext", task.input());
        assertEquals(OfflineJdbcFixture.NOW, task.createdAt());
        assertNull(task.startedAt());
        assertEquals(task, repository.findById(task.id()).orElseThrow());
        assertEquals(0, fixture.rows.get(task.id()).version());
        assertEquals(TaskInputRules.NORMALIZATION_VERSION, fixture.rows.get(task.id()).normalizationVersion());
    }

    @Test
    void normalizedDuplicatesReuseTaskBeforeAndAfterCompletion() {
        var first = repository.createOrGet("duplicate", "e\u0301\r\ntext");
        var second = repository.createOrGet("duplicate", "é\ntext");
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.task().id(), second.task().id());
        repository.start(first.task().id());
        repository.succeed(first.task().id(), "output");
        var terminal = repository.createOrGet("duplicate", "é\ntext");
        assertFalse(terminal.created());
        assertEquals(TaskStatus.SUCCESS, terminal.task().status());
        assertEquals(1, fixture.rows.size());
    }

    @Test
    void differentInputConflictsWithoutChangingStoredTask() {
        var first = repository.createOrGet("conflict", "hello").task();
        var ex = assertThrows(TaskException.class, () -> repository.createOrGet("conflict", "hello "));
        assertEquals(TaskException.Kind.IDEMPOTENCY_CONFLICT, ex.kind());
        assertEquals(first, repository.findById(first.id()).orElseThrow());
    }

    @Test
    void rollsBackDuplicateInsertBeforeReadingInAFreshTransaction() {
        repository.createOrGet("tx", "hello");
        fixture.operations.clear();
        fixture.completions.clear();
        repository.createOrGet("tx", "hello");
        assertEquals(2, fixture.completions.size());
        var insert = fixture.completions.get(0);
        var lookup = fixture.completions.get(1);
        assertFalse(insert.committed());
        assertFalse(insert.readOnly());
        assertTrue(lookup.committed());
        assertTrue(lookup.readOnly());
        assertNotEquals(insert.transactionId(), lookup.transactionId());
        assertTrue(fixture.operations.get(0).sql().stripLeading().startsWith("INSERT"), "No SELECT before INSERT");
        assertTrue(fixture.operations.get(1).sql().contains("WHERE idempotency_key = ?"));
        assertFalse(fixture.transactions.isActive());
    }

    @Test
    void multipleRepositoryObjectsHandleConcurrentDuplicatesThroughSqlContract() throws Exception {
        var results = race(24, index -> fixture.repository().createOrGet("shared", "hello"));
        assertEquals(1, results.stream().filter(TaskRepository.Registration::created).count());
        assertEquals(1, results.stream().map(result -> result.task().id()).distinct().count());
        assertEquals(1, fixture.rows.size());
    }

    @Test
    void concurrentDifferentInputsProduceOneWinnerAndExplicitConflicts() throws Exception {
        var results = race(12, index -> {
            try {
                return fixture.repository().createOrGet("shared-conflict", "input-" + index).created();
            } catch (TaskException ex) {
                assertEquals(TaskException.Kind.IDEMPOTENCY_CONFLICT, ex.kind());
                return false;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, fixture.rows.size());
    }

    @Test
    void onlyOneConcurrentClaimSucceeds() throws Exception {
        UUID id = repository.createOrGet("claim", "hello").task().id();
        var results = race(2, index -> {
            try {
                fixture.repository().start(id);
                return true;
            } catch (TaskStateConflictException ex) { return false; }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(TaskStatus.RUNNING, repository.findById(id).orElseThrow().status());
        assertEquals(1, fixture.rows.get(id).version());
    }

    @Test
    void successAndFailureRaceCannotOverwriteEachOther() throws Exception {
        UUID id = repository.createOrGet("completion-race", "hello").task().id();
        repository.start(id);
        var results = race(2, index -> {
            try {
                if (index == 0) { fixture.repository().succeed(id, "output"); }
                else { fixture.repository().fail(id, TaskStatus.RUNNING, "MODEL_CALL_FAILED"); }
                return true;
            } catch (TaskStateConflictException ex) { return false; }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        var terminal = repository.findById(id).orElseThrow();
        assertTrue(terminal.status() == TaskStatus.SUCCESS || terminal.status() == TaskStatus.FAILED);
        assertEquals(2, fixture.rows.get(id).version());
        assertNotNull(terminal.completedAt());
        if (terminal.status() == TaskStatus.SUCCESS) { assertNull(terminal.errorCode()); }
        else { assertNull(terminal.output()); }
        assertThrows(TaskStateConflictException.class, () -> repository.succeed(id, "overwritten"));
        assertThrows(TaskStateConflictException.class, () -> repository.fail(id, TaskStatus.RUNNING, "ERROR"));
        assertEquals(terminal, repository.findById(id).orElseThrow());
    }

    @Test
    void pendingRejectionCannotFailAnAlreadyClaimedTask() {
        UUID id = repository.createOrGet("stale-reject", "hello").task().id();
        repository.start(id);
        assertThrows(TaskStateConflictException.class,
                () -> repository.fail(id, TaskStatus.PENDING, "EXECUTION_REJECTED"));
        assertEquals(TaskStatus.RUNNING, repository.findById(id).orElseThrow().status());
    }

    @Test
    void rejectedPendingTaskRemainsTerminalAndIdempotent() {
        UUID id = repository.createOrGet("rejected", "hello").task().id();
        var failed = repository.fail(id, TaskStatus.PENDING, "EXECUTION_REJECTED");
        assertNull(failed.startedAt());
        assertNotNull(failed.completedAt());
        assertThrows(TaskStateConflictException.class, () -> repository.start(id));
        assertEquals(TaskStatus.FAILED, repository.createOrGet("rejected", "hello").task().status());
        assertEquals(1, fixture.rows.get(id).version());
    }

    @Test
    void duplicateWithoutMatchingKeyIsNotMisreportedAsIdempotency() {
        var unrelated = new DuplicateKeyException("Simulated primary key collision");
        fixture.insertFailure = unrelated;
        assertSame(unrelated, assertThrows(DuplicateKeyException.class,
                () -> repository.createOrGet("unrelated", "hello")));
        assertTrue(fixture.rows.isEmpty());
    }

    @Test
    void lockFailureDoesNotEnterTheDuplicateKeyRecoveryPath() {
        var lock = new CannotAcquireLockException("Simulated deadlock");
        fixture.insertFailure = lock;
        assertSame(lock, assertThrows(CannotAcquireLockException.class,
                () -> repository.createOrGet("lock", "hello")));
        assertEquals(1, fixture.operations.size());
        assertFalse(fixture.completions.get(0).committed());
    }

    @Test
    void missingTasksReturnEmptyOrNotFound() {
        UUID missing = UUID.randomUUID();
        assertTrue(repository.findById(missing).isEmpty());
        assertEquals(TaskException.Kind.TASK_NOT_FOUND,
                assertThrows(TaskException.class, () -> repository.start(missing)).kind());
        assertEquals(TaskException.Kind.TASK_NOT_FOUND,
                assertThrows(TaskException.class, () -> repository.fail(missing, TaskStatus.PENDING, "ERROR")).kind());
    }

    @Test
    void validatesWritesBeforeIssuingSql() {
        assertThrows(TaskException.class, () -> repository.createOrGet("bad/key", "hello"));
        assertThrows(TaskException.class, () -> repository.createOrGet("key", "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> repository.succeed(UUID.randomUUID(), " "));
        assertThrows(IllegalArgumentException.class, () -> repository.succeed(UUID.randomUUID(), "a".repeat(65536)));
        assertThrows(IllegalArgumentException.class, () -> repository.fail(UUID.randomUUID(), TaskStatus.PENDING, "bad code"));
        assertThrows(IllegalStateException.class, () -> repository.fail(UUID.randomUUID(), TaskStatus.SUCCESS, "ERROR"));
        assertTrue(fixture.operations.isEmpty());
    }

    @Test
    void unsupportedNormalizationVersionFailsRatherThanReusingUnknownSemantics() {
        UUID id = repository.createOrGet("old-version", "hello").task().id();
        var stored = fixture.rows.get(id);
        fixture.rows.put(id, new OfflineJdbcFixture.Stored(stored.task(), "UNKNOWN", stored.version()));
        assertThrows(DataIntegrityViolationException.class, () -> repository.findById(id));
    }

    @Test
    void unexpectedAffectedRowCountDoesNotPretendClaimSucceeded() {
        UUID id = repository.createOrGet("count", "hello").task().id();
        fixture.forcedUpdateCount = 2;
        assertThrows(DataIntegrityViolationException.class, () -> repository.start(id));
        assertEquals(TaskStatus.PENDING, repository.findById(id).orElseThrow().status());
    }

    @Test
    void modelCallRunsAfterClaimCommitAndOutsideRepositoryTransactions() {
        List<Runnable> queue = new ArrayList<>();
        TaskService service = new TaskService(repository, input -> {
            assertFalse(fixture.transactions.isActive());
            assertEquals(TaskStatus.RUNNING, fixture.rows.values().iterator().next().task().status());
            assertTrue(fixture.completions.get(fixture.completions.size() - 1).committed());
            return new MockModelClient().process(input);
        }, queue::add);
        UUID id = service.submit("model-tx", "hello").task().id();
        assertFalse(fixture.transactions.isActive());
        queue.get(0).run();
        assertEquals(TaskStatus.SUCCESS, repository.findById(id).orElseThrow().status());
        assertFalse(fixture.transactions.isActive());
    }

    @Test
    void mysqlDuplicateErrorTranslationIsExplicitAndOffline() {
        var translator = new SQLErrorCodeSQLExceptionTranslator("MySQL");
        var duplicate = translator.translate("insert", "test SQL", new SQLException("duplicate", "23000", 1062));
        assertInstanceOf(DuplicateKeyException.class, duplicate);
        var deadlock = translator.translate("insert", "test SQL", new SQLException("deadlock", "40001", 1213));
        assertNotNull(deadlock);
        assertFalse(deadlock instanceof DuplicateKeyException);
    }

    private <T> List<T> race(int count, IntFunction<T> action) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(count, 8));
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int worker = index;
                futures.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return action.apply(worker);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) { results.add(future.get(5, TimeUnit.SECONDS)); }
            return results;
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
