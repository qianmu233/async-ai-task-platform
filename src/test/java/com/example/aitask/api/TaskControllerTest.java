package com.example.aitask.api;

import com.example.aitask.model.MockModelClient;
import com.example.aitask.repository.InMemoryTaskRepository;
import com.example.aitask.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskControllerTest {
    private MockMvc mvc;
    private TaskService service;
    private final List<Runnable> queue = new ArrayList<>();

    @BeforeEach
    void setup() {
        service = new TaskService(new InMemoryTaskRepository(), new MockModelClient(), queue::add);
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void submissionAndDuplicateUseDifferentHttpCodes() throws Exception {
        String body = "{\"idempotencyKey\":\"api-test\",\"input\":\"hello\"}";
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.created").value(true)).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"api-test\",\"input\":\"different\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void resultMovesFromAcceptedToSuccess() throws Exception {
        var id = service.submit("result", "hello").task().id();
        mvc.perform(get("/api/tasks/{id}/result", id)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        queue.get(0).run();
        mvc.perform(get("/api/tasks/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(get("/api/tasks/{id}/result", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.output").value("[MOCK] hello"));
    }

    @Test
    void failedResultHasStableErrorCode() throws Exception {
        var id = service.submit("fail", "[MOCK_FAIL]").task().id();
        queue.get(0).run();
        mvc.perform(get("/api/tasks/{id}/result", id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("MODEL_CALL_FAILED"));
    }

    @Test
    void validatesBodyAndTaskId() throws Exception {
        for (String body : List.of("{}", "null", "{", "{\"idempotencyKey\":\"key\",\"input\":\" \"}")) {
            mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/api/tasks/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/{id}/result", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void rejectionReturnsQueryableTaskWith503() throws Exception {
        var rejecting = new TaskService(new InMemoryTaskRepository(), new MockModelClient(), command -> {
            throw new RejectedExecutionException();
        });
        var rejectingMvc = MockMvcBuilders.standaloneSetup(new TaskController(rejecting))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        rejectingMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"full\",\"input\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }
}
