package com.example.aitask.service;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.model.MockModelClient;
import com.example.aitask.repository.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Memory/clock unit tests. Not evidence of real SQL locks, database time, or cross-JVM execution. */
class TaskExecutionTimeoutTest {
    static class Time extends Clock {
        Instant now = Instant.parse("2026-10-14T04:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private final Time clock = new Time();
    private final InMemoryTaskRepository repository = new InMemoryTaskRepository(clock, 1000);
    private final TaskTimeoutChecker checker = new TaskTimeoutChecker(repository, 1);
    private UUID pending(String key) { return repository.createOrGet(key, "hello").task().id(); }

    @Test
    void onlyValidOwnerCanCompleteAndTokenlessOperationsAreRefused() {
        UUID id = pending("normal");
        var claim = repository.claimExecution(id);
        assertThat(claim.token()).isNotNull();
        assertThat(claim.deadline()).isEqualTo(clock.now.plusMillis(1000));
        var other = new TaskRepository.Execution(claim.task(), UUID.randomUUID(), claim.deadline());
        assertThatThrownBy(() -> repository.succeedExecution(other, "other output")).isInstanceOf(TaskStateConflictException.class);
        assertThatThrownBy(() -> repository.failExecution(other, "MODEL_CALL_FAILED")).isInstanceOf(TaskStateConflictException.class);
        assertThatThrownBy(() -> repository.succeed(id, "tokenless")).isInstanceOf(IllegalStateException.class);
        assertThat(repository.succeedExecution(claim, "valid output").status()).isEqualTo(TaskStatus.SUCCESS);
        assertThatThrownBy(() -> repository.failExecution(claim, "MODEL_CALL_FAILED")).isInstanceOf(TaskStateConflictException.class);
        assertThat(checker.check(id)).isZero();
        UUID modelFailure = repository.createOrGet("failed-model", "[MOCK_FAIL]").task().id();
        assertThat(new TaskWorker(repository, new MockModelClient()).execute(modelFailure)).isEqualTo(TaskWorker.Outcome.COMPLETED);
        assertThat(repository.findById(modelFailure).orElseThrow().errorCode()).isEqualTo("MODEL_CALL_FAILED");
    }

    @Test
    void deadlineExpiresBeforeScannerAndOldOwnerCannotOverwriteTimeout() {
        UUID id = pending("expired");
        var claim = repository.claimExecution(id);
        clock.now = claim.deadline(); // Exact boundary rejects completion even before the periodic sweep runs.
        assertThatThrownBy(() -> repository.succeedExecution(claim, "late output")).isInstanceOf(TaskStateConflictException.class);
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(checker.check(id)).isEqualTo(1);
        var expired = repository.findById(id).orElseThrow();
        assertThat(expired.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(expired.errorCode()).isEqualTo("TASK_TIMEOUT");
        assertThat(expired.output()).isNull();
        assertThat(expired.completedAt()).isEqualTo(clock.now);
        assertThatThrownBy(() -> repository.failExecution(claim, "MODEL_CALL_FAILED")).isInstanceOf(TaskStateConflictException.class);
        assertThatThrownBy(() -> repository.succeedExecution(claim, "late output")).isInstanceOf(TaskStateConflictException.class);
        assertThat(repository.findById(id).orElseThrow()).isEqualTo(expired);
    }

    @Test
    void lateModelOutputOrFailureDoesNotReplaceTimeoutAndModelIsNeverRetried() {
        for (String input : new String[]{"hello", "[MOCK_FAIL]"}) {
            var time = new Time();
            var repo = new InMemoryTaskRepository(time, 1000);
            UUID id = repo.createOrGet("late-model", input).task().id();
            var calls = new AtomicInteger();
            var worker = new TaskWorker(repo, text -> {
                calls.incrementAndGet();
                time.now = time.now.plusMillis(1000);
                assertThat(new TaskTimeoutChecker(repo, 1).check(id)).isEqualTo(1);
                return new MockModelClient().process(text);
            });
            assertThat(worker.execute(id)).isEqualTo(TaskWorker.Outcome.TERMINAL_DUPLICATE);
            assertThat(repo.findById(id).orElseThrow().errorCode()).isEqualTo("TASK_TIMEOUT");
            assertThat(worker.execute(id)).isEqualTo(TaskWorker.Outcome.TERMINAL_DUPLICATE);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void twoTimeoutHandlersHaveOnlyOneSuccessfulTransition() throws Exception {
        UUID id = pending("race");
        var claim = repository.claimExecution(id);
        clock.now = claim.deadline();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> a = pool.submit(() -> { start.await(); return checker.check(id); });
            Future<Integer> b = pool.submit(() -> { start.await(); return new TaskTimeoutChecker(repository, 1).check(id); });
            start.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS) + b.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(checker.check(id)).isZero();
            assertThat(repository.findById(id).orElseThrow().errorCode()).isEqualTo("TASK_TIMEOUT");
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void batchAndTaskScopeDoNotExpireUnselectedExecutions() {
        UUID a = pending("scope-a"), b = pending("scope-b");
        repository.claimExecution(a); repository.claimExecution(b);
        clock.now = clock.now.plusMillis(1000);
        assertThat(checker.check(a)).isEqualTo(1);
        assertThat(repository.findById(b).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(checker.check(UUID.randomUUID())).isZero();
        assertThat(checker.check(null)).isEqualTo(1);
    }
}
