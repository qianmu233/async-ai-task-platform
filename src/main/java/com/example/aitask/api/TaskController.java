package com.example.aitask.api;

import com.example.aitask.domain.TaskStatus;
import com.example.aitask.domain.TextTask;
import com.example.aitask.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    public record SubmitRequest(String idempotencyKey, String input) { }
    public record SubmitResponse(UUID taskId, TaskStatus status, boolean created) { }
    public record StatusResponse(UUID taskId, TaskStatus status, String errorCode,
                                 Instant createdAt, Instant startedAt, Instant completedAt) { }
    public record ResultResponse(UUID taskId, TaskStatus status, String output, String errorCode) { }

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SubmitResponse> submit(@RequestBody SubmitRequest request) {
        var registration = service.submit(request.idempotencyKey(), request.input());
        TextTask task = registration.task();
        HttpStatus responseStatus = !registration.created() ? HttpStatus.OK
                : task.status() == TaskStatus.FAILED ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.ACCEPTED;
        return ResponseEntity.status(responseStatus)
                .location(URI.create("/api/tasks/" + task.id()))
                .body(new SubmitResponse(task.id(), task.status(), registration.created()));
    }

    @GetMapping("/{taskId}")
    public StatusResponse status(@PathVariable UUID taskId) {
        TextTask task = service.get(taskId);
        return new StatusResponse(task.id(), task.status(), task.errorCode(),
                task.createdAt(), task.startedAt(), task.completedAt());
    }

    @GetMapping("/{taskId}/result")
    public ResponseEntity<ResultResponse> result(@PathVariable UUID taskId) {
        TextTask task = service.get(taskId);
        HttpStatus status = switch (task.status()) {
            case PENDING, RUNNING -> HttpStatus.ACCEPTED;
            case SUCCESS -> HttpStatus.OK;
            case FAILED -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(
                new ResultResponse(task.id(), task.status(), task.output(), task.errorCode()));
    }
}
