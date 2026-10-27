package com.example.aitask.domain;

import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import com.example.aitask.service.TaskInputRules;

/** Immutable snapshots avoid exposing partially updated state to readers. */
public record TextTask(UUID id, String idempotencyKey, String input, TaskStatus status,
                       String output, String errorCode, Instant createdAt,
                       Instant startedAt, Instant completedAt) {
    public static TextTask pending(String key, String input, Instant now) {
        return new TextTask(UUID.randomUUID(), key, input, TaskStatus.PENDING,
                null, null, now, null, null);
    }

    public TextTask start(Instant now) {
        status.requireTransitionTo(TaskStatus.RUNNING);
        return new TextTask(id, idempotencyKey, input, TaskStatus.RUNNING,
                null, null, createdAt, now, null);
    }

    public TextTask succeed(String result, Instant now) {
        status.requireTransitionTo(TaskStatus.SUCCESS);
        validateOutput(result);
        return new TextTask(id, idempotencyKey, input, TaskStatus.SUCCESS,
                result, null, createdAt, startedAt, now);
    }

    public TextTask fail(String code, Instant now) {
        status.requireTransitionTo(TaskStatus.FAILED);
        validateErrorCode(code);
        return new TextTask(id, idempotencyKey, input, TaskStatus.FAILED,
                null, code, createdAt, startedAt, now);
    }

    public static void validateOutput(String result) {
        if (result == null || result.isBlank() || !TaskInputRules.isWellFormedUnicode(result)
                || result.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw new IllegalArgumentException("Model output must be valid non-blank Unicode within 65535 UTF-8 bytes");
        }
    }

    public static void validateErrorCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Failure code must contain 1-64 uppercase ASCII letters, digits or underscores");
        }
    }
}
