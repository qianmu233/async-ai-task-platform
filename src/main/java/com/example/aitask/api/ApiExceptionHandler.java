package com.example.aitask.api;

import com.example.aitask.service.TaskException;
import com.example.aitask.service.TaskDispatchException;
import com.example.aitask.repository.TaskStateConflictException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ApiError(String code, String message) { }
    public record DispatchError(String code, String message, java.util.UUID taskId) { }

    @ExceptionHandler(com.example.aitask.ratelimit.RateLimitException.class)
    public ResponseEntity<ApiError> rateLimitError(com.example.aitask.ratelimit.RateLimitException ex) {
        if (ex.retryAfterSeconds() > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(ex.retryAfterSeconds()))
                    .body(new ApiError("RATE_LIMIT_EXCEEDED", "Task submission rate exceeded; retry later"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(
                "RATE_LIMIT_UNAVAILABLE", "Submission rate limiter unavailable; task was not submitted"));
    }

    @ExceptionHandler(TaskDispatchException.class)
    public ResponseEntity<DispatchError> dispatchError(TaskDispatchException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new DispatchError(
                "DISPATCH_UNAVAILABLE", "Task dispatch not confirmed; retry with the same idempotency key", ex.taskId()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storageError(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(
                "STORAGE_UNAVAILABLE", "Task storage is unavailable; retry with the same idempotency key"));
    }

    @ExceptionHandler(TaskStateConflictException.class)
    public ResponseEntity<ApiError> stateConflict(TaskStateConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                "TASK_STATE_CONFLICT", "Task state changed before this operation completed"));
    }

    @ExceptionHandler(TaskException.class)
    public ResponseEntity<ApiError> taskError(TaskException ex) {
        HttpStatus status = switch (ex.kind()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(new ApiError(ex.kind().name(), ex.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> malformedRequest(Exception ex) {
        return ResponseEntity.badRequest().body(new ApiError(
                "INVALID_REQUEST", "Request body must match the JSON schema and taskId must be a UUID"));
    }
}
