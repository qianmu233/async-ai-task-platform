package com.example.aitask.service;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.domain.TextTask;
import com.example.aitask.model.ModelClient;
import com.example.aitask.model.ModelCallException;
import com.example.aitask.repository.TaskRepository;
import com.example.aitask.repository.TaskStateConflictException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.util.UUID;

/** Shared execution semantics; the transport decides when/how to acknowledge failures. */
@Component
public class TaskWorker {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskWorker.class);
    private static void storageDiagnostic(Stage stage, RuntimeException failure) {
        String state = "NONE"; int vendor = 0;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql) {
                String candidate = sql.getSQLState();
                state = candidate != null && candidate.matches("[A-Z0-9]{5}") ? candidate : "UNKNOWN";
                vendor = sql.getErrorCode(); break;
            }
        }
        log.error("TASK_STORAGE_FAILURE stage={} type={} sqlState={} vendorCode={}", stage,
                failure.getClass().getSimpleName(), state, vendor); // No raw messages, SQL, input or causes.
    }
    public enum Outcome { COMPLETED, TERMINAL_DUPLICATE, IN_PROGRESS }
    public enum Stage { CLAIM, COMPLETION }

    public static class StorageFailure extends RuntimeException {
        private final Stage stage;
        public StorageFailure(Stage stage) {
            super("Task storage failed during " + stage);
            this.stage = stage;
        }
        public Stage stage() { return stage; }
    }

    private final TaskRepository repository;
    private final ModelClient model;

    public TaskWorker(TaskRepository repository, ModelClient model) {
        this.repository = repository;
        this.model = model;
    }

    public Outcome execute(UUID id) {
        TaskRepository.Execution running;
        try {
            try {
                running = repository.claimExecution(id);
            } catch (TaskStateConflictException conflict) {
                return observedOutcome(id);
            }
        } catch (DataAccessException | TransactionException failure) {
            storageDiagnostic(Stage.CLAIM, failure);
            throw new StorageFailure(Stage.CLAIM);
        }

        String output;
        try {
            output = model.process(running.task().input());
            TextTask.validateOutput(output);
        } catch (ModelCallException modelFailure) {
            return finish(running, null, modelFailure.errorCode());
        } catch (RuntimeException modelFailure) {
            return finish(running, null, "MODEL_CALL_FAILED");
        }
        return finish(running, output, null);
    }

    private Outcome finish(TaskRepository.Execution execution, String output, String error) {
        try {
            try {
                if (error == null) { repository.succeedExecution(execution, output); }
                else { repository.failExecution(execution, error); }
                return Outcome.COMPLETED;
            } catch (TaskStateConflictException conflict) {
                return observedOutcome(execution.task().id());
            }
        } catch (DataAccessException | TransactionException failure) {
            storageDiagnostic(Stage.COMPLETION, failure);
            // No model retry: the model may have run and the terminal commit may be ambiguous.
            throw new StorageFailure(Stage.COMPLETION);
        }
    }

    private Outcome observedOutcome(UUID id) {
        var task = repository.findById(id).orElseThrow(() ->
                new TaskException(TaskException.Kind.TASK_NOT_FOUND, "Task not found"));
        return switch (task.status()) {
            case SUCCESS, FAILED -> Outcome.TERMINAL_DUPLICATE;
            case RUNNING -> Outcome.IN_PROGRESS;
            case PENDING -> throw new IllegalStateException("Conflicting task remains pending");
        };
    }
}
