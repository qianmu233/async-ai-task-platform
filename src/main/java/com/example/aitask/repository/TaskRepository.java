package com.example.aitask.repository;

import com.example.aitask.domain.TextTask;
import com.example.aitask.domain.TaskStatus;
import com.example.aitask.service.TaskException;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface TaskRepository {
    record Registration(TextTask task, boolean created) { }
    /** Internal execution handle. NULL metadata means explicitly disabled legacy mode, never a protected claim. */
    record Execution(TextTask task, UUID token, Instant deadline) {
        public Execution {
            if ((token == null) != (deadline == null)) { throw new IllegalArgumentException("Execution ownership must be paired"); }
        }
    }

    /** Atomically register the key and normalized input, or return the existing identical request. */
    Registration createOrGet(String key, String input);
    Optional<TextTask> findById(UUID id);
    TextTask start(UUID id);
    TextTask succeed(UUID id, String output);
    TextTask fail(UUID id, TaskStatus expectedStatus, String errorCode);

    default Execution claimExecution(UUID id) { return new Execution(start(id), null, null); }
    default TextTask succeedExecution(Execution execution, String output) {
        requireLegacy(execution);
        return succeed(execution.task().id(), output);
    }
    default TextTask failExecution(Execution execution, String code) {
        requireLegacy(execution);
        return fail(execution.task().id(), TaskStatus.RUNNING, code);
    }
    default int expireRunning(int batchSize, UUID taskScope) { throw new IllegalStateException("Execution timeouts not enabled"); }
    default void verifyExecutionTimeoutSchema() { throw new IllegalStateException("Execution timeouts not supported"); }
    static void validateExecutionTimeout(long millis) {
        if (millis < 100 || millis > 86400000) { throw new IllegalArgumentException("Execution timeout must be 100-86400000ms"); }
    }
    static void validateTimeoutBatch(int size) {
        if (size < 1 || size > 100) { throw new IllegalArgumentException("Timeout batch must be 1-100"); }
    }
    private static void requireLegacy(Execution execution) {
        if (execution.token() != null) { throw new IllegalStateException("Protected execution requires an ownership-aware repository"); }
    }

    /** Compatibility helper; the final update must still compare the observed state atomically. */
    default TextTask fail(UUID id, String errorCode) {
        TextTask current = findById(id).orElseThrow(() -> new TaskException(
                TaskException.Kind.TASK_NOT_FOUND, "Task not found"));
        return fail(id, current.status(), errorCode);
    }
}
