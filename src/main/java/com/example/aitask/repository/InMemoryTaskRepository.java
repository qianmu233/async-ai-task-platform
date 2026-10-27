package com.example.aitask.repository;

import com.example.aitask.domain.TextTask;
import com.example.aitask.domain.TaskStatus;
import com.example.aitask.service.TaskException;
import com.example.aitask.service.TaskInputRules;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Repository
@ConditionalOnProperty(name = "platform.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskRepository implements TaskRepository {
    private final Map<UUID, TextTask> tasks = new HashMap<>();
    private final Map<String, UUID> keys = new HashMap<>();
    private final Clock clock;
    private final long executionTimeoutMillis;
    private final Map<UUID, Execution> executions = new HashMap<>();

    public InMemoryTaskRepository() {
        this(Clock.systemUTC());
    }

    public InMemoryTaskRepository(Clock clock) {
        this(clock, 0);
    }

    @Autowired
    public InMemoryTaskRepository(@Value("${platform.execution.timeout-enabled:false}") boolean enabled,
            @Value("${platform.execution.timeout-ms:60000}") long timeoutMillis) {
        this(Clock.systemUTC(), configuredTimeout(enabled, timeoutMillis));
    }

    public InMemoryTaskRepository(Clock clock, long timeoutMillis) {
        this.clock = clock;
        if (timeoutMillis != 0) { TaskRepository.validateExecutionTimeout(timeoutMillis); }
        this.executionTimeoutMillis = timeoutMillis;
    }

    @Override
    public synchronized Registration createOrGet(String key, String input) {
        input = TaskInputRules.normalizeInput(input);
        TaskInputRules.validate(key, input);
        UUID existingId = keys.get(key);
        if (existingId != null) {
            TextTask existing = tasks.get(existingId);
            if (!existing.input().equals(input)) {
                throw new TaskException(TaskException.Kind.IDEMPOTENCY_CONFLICT,
                        "This idempotencyKey was already used with a different input");
            }
            return new Registration(existing, false);
        }
        TextTask task = TextTask.pending(key, input, clock.instant());
        tasks.put(task.id(), task);
        keys.put(key, task.id());
        return new Registration(task, true);
    }

    @Override
    public synchronized Optional<TextTask> findById(UUID id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public synchronized TextTask start(UUID id) {
        requireTokenlessMode();
        TextTask task = requireState(id, TaskStatus.PENDING).start(clock.instant());
        tasks.put(id, task);
        return task;
    }

    @Override
    public synchronized TextTask succeed(UUID id, String output) {
        requireTokenlessMode();
        TextTask task = requireState(id, TaskStatus.RUNNING).succeed(output, clock.instant());
        tasks.put(id, task);
        return task;
    }

    @Override
    public synchronized TextTask fail(UUID id, TaskStatus expectedStatus, String errorCode) {
        if (expectedStatus == TaskStatus.RUNNING) { requireTokenlessMode(); }
        TextTask task = requireState(id, expectedStatus).fail(errorCode, clock.instant());
        tasks.put(id, task);
        return task;
    }

    @Override
    public synchronized Execution claimExecution(UUID id) {
        if (executionTimeoutMillis == 0) { return TaskRepository.super.claimExecution(id); }
        TextTask task = requireState(id, TaskStatus.PENDING).start(clock.instant());
        var execution = new Execution(task, UUID.randomUUID(), task.startedAt().plusMillis(executionTimeoutMillis));
        tasks.put(id, task);
        executions.put(id, execution);
        return execution;
    }

    @Override
    public synchronized TextTask succeedExecution(Execution execution, String output) {
        if (executionTimeoutMillis == 0) { return TaskRepository.super.succeedExecution(execution, output); }
        TextTask task = requireOwned(execution).succeed(output, clock.instant());
        tasks.put(task.id(), task); executions.remove(task.id());
        return task;
    }

    @Override
    public synchronized TextTask failExecution(Execution execution, String code) {
        if (executionTimeoutMillis == 0) { return TaskRepository.super.failExecution(execution, code); }
        TextTask task = requireOwned(execution).fail(code, clock.instant());
        tasks.put(task.id(), task); executions.remove(task.id());
        return task;
    }

    @Override
    public synchronized int expireRunning(int batchSize, UUID taskScope) {
        verifyExecutionTimeoutSchema();
        TaskRepository.validateTimeoutBatch(batchSize);
        var now = clock.instant();
        var expired = executions.values().stream().filter(e -> !e.deadline().isAfter(now)
                        && (taskScope == null || taskScope.equals(e.task().id())))
                .sorted(Comparator.comparing(Execution::deadline).thenComparing(e -> e.task().id()))
                .limit(batchSize).map(e -> e.task().id()).toList();
        for (UUID id : expired) {
            tasks.put(id, requireState(id, TaskStatus.RUNNING).fail("TASK_TIMEOUT", now));
            executions.remove(id);
        }
        return expired.size();
    }

    @Override
    public void verifyExecutionTimeoutSchema() {
        if (executionTimeoutMillis == 0) { throw new IllegalStateException("Execution timeouts not enabled"); }
    }

    private TextTask requireOwned(Execution execution) {
        UUID id = execution.task().id();
        TextTask task = requireState(id, TaskStatus.RUNNING);
        Execution current = executions.get(id);
        if (current == null || !current.token().equals(execution.token()) || !clock.instant().isBefore(current.deadline())) {
            throw new TaskStateConflictException(id, task.status(), TaskStatus.RUNNING);
        }
        return task;
    }

    private void requireTokenlessMode() {
        if (executionTimeoutMillis != 0) { throw new IllegalStateException("Execution ownership token required"); }
    }

    private static long configuredTimeout(boolean enabled, long millis) {
        if (enabled) { TaskRepository.validateExecutionTimeout(millis); }
        return enabled ? millis : 0;
    }

    private TextTask require(UUID id) {
        return findById(id).orElseThrow(() -> new TaskException(
                TaskException.Kind.TASK_NOT_FOUND, "Task not found"));
    }

    private TextTask requireState(UUID id, TaskStatus expected) {
        TextTask current = require(id);
        if (current.status() != expected) {
            throw new TaskStateConflictException(id, current.status(), expected);
        }
        return current;
    }
}
