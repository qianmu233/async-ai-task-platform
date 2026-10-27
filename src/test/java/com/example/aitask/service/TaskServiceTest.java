package com.example.aitask.service;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.model.MockModelClient;
import com.example.aitask.repository.InMemoryTaskRepository;
import com.example.aitask.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TaskServiceTest {
    private final InMemoryTaskRepository repository = new InMemoryTaskRepository();
    private final List<Runnable> queue = new ArrayList<>();
    private final TaskService service = new TaskService(repository, new MockModelClient(), queue::add);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsBlankInput(String input) {
        TaskException ex = assertThrows(TaskException.class, () -> service.submit("key", input));
        assertEquals(TaskException.Kind.INVALID_REQUEST, ex.kind());
        assertTrue(queue.isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "has space", "中文", "key/invalid"})
    void rejectsInvalidKey(String key) {
        assertThrows(TaskException.class, () -> service.submit(key, "text"));
        assertTrue(queue.isEmpty());
    }

    @Test
    void checksLengthBoundaries() {
        assertThrows(TaskException.class, () -> service.submit("k".repeat(129), "text"));
        assertThrows(TaskException.class, () -> service.submit("key", "a".repeat(5001)));
        assertTrue(queue.isEmpty());
        assertTrue(service.submit("k".repeat(128), "a".repeat(5000)).created());
    }

    @Test
    void executesOnceAndReusesTaskBeforeAndAfterCompletion() {
        var first = service.submit("key", " hello ");
        var repeated = service.submit("key", " hello ");
        assertTrue(first.created());
        assertFalse(repeated.created());
        assertEquals(first.task().id(), repeated.task().id());
        assertEquals(TaskStatus.PENDING, service.get(first.task().id()).status());
        assertEquals(1, queue.size());
        queue.get(0).run();
        var completed = service.get(first.task().id());
        assertEquals(TaskStatus.SUCCESS, completed.status());
        assertEquals("[MOCK] hello", completed.output());
        assertNotNull(completed.startedAt());
        assertNotNull(completed.completedAt());
        assertEquals(TaskStatus.SUCCESS, service.submit("key", " hello ").task().status());
        assertEquals(1, queue.size());
    }

    @Test
    void conflictsOnChangedInputWithoutNormalizingWhitespace() {
        var original = service.submit("key", "hello");
        TaskException ex = assertThrows(TaskException.class, () -> service.submit("key", "hello "));
        assertEquals(TaskException.Kind.IDEMPOTENCY_CONFLICT, ex.kind());
        assertEquals("hello", service.get(original.task().id()).input());
        assertEquals(1, queue.size());
    }

    @Test
    void marksModelFailureAndDoesNotRetrySameKey() {
        var registration = service.submit("failure", "[MOCK_FAIL]");
        queue.get(0).run();
        var failed = service.get(registration.task().id());
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals("MODEL_CALL_FAILED", failed.errorCode());
        assertNull(failed.output());
        assertFalse(service.submit("failure", "[MOCK_FAIL]").created());
        assertEquals(1, queue.size());
    }

    @Test
    void makesRunningStateObservableDuringModelCall() {
        UUID[] id = new UUID[1];
        TaskService observing = new TaskService(repository, input -> {
            assertEquals(TaskStatus.RUNNING, repository.findById(id[0]).orElseThrow().status());
            return "ok";
        }, queue::add);
        id[0] = observing.submit("running", "hello").task().id();
        queue.get(0).run();
        assertEquals(TaskStatus.SUCCESS, observing.get(id[0]).status());
    }

    @Test
    void treatsInvalidModelOutputAsFailure() {
        TaskService invalidModel = new TaskService(repository, input -> null, queue::add);
        var task = invalidModel.submit("null-output", "hello").task();
        queue.get(0).run();
        assertEquals("MODEL_CALL_FAILED", invalidModel.get(task.id()).errorCode());
    }

    @Test
    void preservesRejectedTaskAndItsIdempotencyKey() {
        TaskService rejecting = new TaskService(repository, new MockModelClient(), command -> {
            throw new RejectedExecutionException("full");
        });
        var result = rejecting.submit("rejected", "hello");
        assertTrue(result.created());
        assertEquals(TaskStatus.FAILED, result.task().status());
        assertEquals("EXECUTION_REJECTED", result.task().errorCode());
        assertNull(result.task().startedAt());
        assertFalse(rejecting.submit("rejected", "hello").created());
    }

    @Test
    void reportsMissingTask() {
        TaskException ex = assertThrows(TaskException.class, () -> service.get(UUID.randomUUID()));
        assertEquals(TaskException.Kind.TASK_NOT_FOUND, ex.kind());
    }

    @Test
    void concurrentDuplicatesRegisterAndScheduleOnlyOnce() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        TaskService concurrentService = new TaskService(repository, new MockModelClient(),
                command -> dispatches.incrementAndGet());
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<TaskRepository.Registration>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(callers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return concurrentService.submit("concurrent", "hello");
                }));
            }
            start.countDown();
            List<TaskRepository.Registration> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream().filter(TaskRepository.Registration::created).count());
            assertEquals(1, results.stream().map(result -> result.task().id()).distinct().count());
            assertEquals(1, dispatches.get());
        } finally {
            callers.shutdownNow();
        }
    }
}
