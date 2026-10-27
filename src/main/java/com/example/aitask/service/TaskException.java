package com.example.aitask.service;

public class TaskException extends RuntimeException {
    public enum Kind { INVALID_REQUEST, IDEMPOTENCY_CONFLICT, TASK_NOT_FOUND }

    private final Kind kind;

    public TaskException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
