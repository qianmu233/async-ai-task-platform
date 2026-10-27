package com.example.aitask.service;

import java.util.UUID;

/** Credential-free publication failure; the task stays queryable and retryable under its original key. */
public class TaskDispatchException extends RuntimeException {
    private final UUID taskId;
    public TaskDispatchException(UUID taskId) {
        super("Task dispatch not confirmed; retry the same idempotency key");
        this.taskId = taskId;
    }
    public UUID taskId() { return taskId; }
}
