package com.example.aitask.service;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.domain.TextTask;
import com.example.aitask.model.MockModelClient;
import com.example.aitask.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TaskPersistenceSemanticsTest {
    private final List<Runnable> queue = new ArrayList<>();

    @Test
    void normalizationIsSharedByMemoryAndSubmissionWithoutTrimmingOrChangingCase() {
        var service = new TaskService(new InMemoryTaskRepository(), new MockModelClient(), queue::add);
        var first = service.submit("normal", "e\u0301\r\nLine\rEnd").task();
        var duplicate = service.submit("normal", "é\nLine\nEnd");
        assertEquals(first.id(), duplicate.task().id());
        assertEquals("é\nLine\nEnd", first.input());
        assertEquals(1, queue.size());
        assertThrows(TaskException.class, () -> service.submit("normal", "é\nLine\nEnd "));
        assertThrows(TaskException.class, () -> service.submit("normal", "é\nline\nEnd"));
    }

    @Test
    void rejectsMalformedUnicodeAndAcceptsLengthAfterNormalization() {
        var service = new TaskService(new InMemoryTaskRepository(), new MockModelClient(), queue::add);
        for (String invalid : List.of("\uD800", "\uDC00", "\uD800x")) {
            assertThrows(TaskException.class, () -> service.submit("invalid", invalid));
        }
        assertTrue(queue.isEmpty());
        assertEquals(2500, service.submit("length", "e\u0301".repeat(2500)).task().input().length());
    }

    @Test
    void duplicateRunnableCannotCallModelTwice() throws Exception {
        var repository = new InMemoryTaskRepository();
        var modelCalls = new AtomicInteger();
        var service = new TaskService(repository, input -> {
            modelCalls.incrementAndGet();
            return "output";
        }, queue::add);
        UUID id = service.submit("double-run", "hello").task().id();
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Void> work = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                queue.get(0).run();
                return null;
            };
            Future<Void> first = workers.submit(work);
            Future<Void> second = workers.submit(work);
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, modelCalls.get());
            assertEquals(TaskStatus.SUCCESS, service.get(id).status());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void claimStorageFailureDoesNotCallModelOrMarkTaskFailed(boolean transactionFailure) {
        var repository = new FaultRepository();
        repository.transactionFailure = transactionFailure;
        repository.failClaim = true;
        var calls = new AtomicInteger();
        var service = new TaskService(repository, input -> { calls.incrementAndGet(); return "ok"; }, queue::add);
        UUID id = service.submit("claim-failure", "hello").task().id();
        assertDoesNotThrow(() -> queue.get(0).run());
        assertEquals(0, calls.get());
        assertEquals(0, repository.failureWrites);
        assertEquals(TaskStatus.PENDING, service.get(id).status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successStorageFailureLeavesRunningWithoutMisreportingModelFailure(boolean transactionFailure) {
        var repository = new FaultRepository();
        repository.transactionFailure = transactionFailure;
        repository.failSuccess = true;
        var service = new TaskService(repository, new MockModelClient(), queue::add);
        UUID id = service.submit("write-failure", "hello").task().id();
        assertDoesNotThrow(() -> queue.get(0).run());
        assertEquals(TaskStatus.RUNNING, service.get(id).status());
        assertEquals(0, repository.failureWrites);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguousSuccessCommitIsNotOverwrittenWithFailed(boolean transactionFailure) {
        var repository = new FaultRepository();
        repository.transactionFailure = transactionFailure;
        repository.failSuccessAfterCommit = true;
        var service = new TaskService(repository, new MockModelClient(), queue::add);
        UUID id = service.submit("ambiguous-commit", "hello").task().id();
        assertDoesNotThrow(() -> queue.get(0).run());
        assertEquals(TaskStatus.SUCCESS, service.get(id).status());
        assertEquals("[MOCK] hello", service.get(id).output());
        assertEquals(0, repository.failureWrites);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureStorageOutageCanLeaveRunningAndIsNotAutomaticallyRetried(boolean transactionFailure) {
        var repository = new FaultRepository();
        repository.transactionFailure = transactionFailure;
        repository.failFailure = true;
        var service = new TaskService(repository, new MockModelClient(), queue::add);
        UUID id = service.submit("failed-write", "[MOCK_FAIL]").task().id();
        assertDoesNotThrow(() -> queue.get(0).run());
        assertEquals(TaskStatus.RUNNING, service.get(id).status());
        assertEquals(1, repository.failureWrites);
        assertFalse(service.submit("failed-write", "[MOCK_FAIL]").created());
        assertEquals(1, queue.size());
    }

    @Test
    void rejectionDoesNotOverwriteAConcurrentClaim() {
        var repository = new FaultRepository();
        var service = new TaskService(repository, new MockModelClient(), runnable -> {
            repository.start(repository.lastId);
            throw new RejectedExecutionException();
        });
        var result = service.submit("reject-race", "hello");
        assertEquals(TaskStatus.RUNNING, result.task().status());
        assertEquals(TaskStatus.PENDING, repository.lastExpectedFailureStatus);
    }

    @Test
    void modelOutputRespectsMysqlTextCapacityAndUnicodeValidity() {
        assertThrows(IllegalArgumentException.class, () -> TextTask.validateOutput("界".repeat(21846)));
        assertThrows(IllegalArgumentException.class, () -> TextTask.validateOutput("\uD800"));
        assertDoesNotThrow(() -> TextTask.validateOutput("界".repeat(21845)));
    }

    private static class FaultRepository extends InMemoryTaskRepository {
        boolean failClaim;
        boolean failSuccess;
        boolean failSuccessAfterCommit;
        boolean failFailure;
        boolean transactionFailure;
        int failureWrites;
        UUID lastId;
        TaskStatus lastExpectedFailureStatus;

        @Override
        public synchronized Registration createOrGet(String key, String input) {
            var result = super.createOrGet(key, input);
            lastId = result.task().id();
            return result;
        }

        @Override
        public synchronized TextTask start(UUID id) {
            if (failClaim) { throw outage(); }
            return super.start(id);
        }

        @Override
        public synchronized TextTask succeed(UUID id, String output) {
            if (failSuccess) { throw outage(); }
            var result = super.succeed(id, output);
            if (failSuccessAfterCommit) { throw outage(); }
            return result;
        }

        @Override
        public synchronized TextTask fail(UUID id, TaskStatus expected, String code) {
            failureWrites++;
            lastExpectedFailureStatus = expected;
            if (failFailure) { throw outage(); }
            return super.fail(id, expected, code);
        }

        private RuntimeException outage() {
            return transactionFailure ? new TransactionSystemException("Simulated transaction failure")
                    : new DataAccessResourceFailureException("Simulated storage outage");
        }
    }
}
