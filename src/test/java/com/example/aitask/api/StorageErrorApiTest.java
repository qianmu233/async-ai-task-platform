package com.example.aitask.api;

import com.example.aitask.model.MockModelClient;
import com.example.aitask.repository.InMemoryTaskRepository;
import com.example.aitask.service.TaskService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import com.example.aitask.domain.TextTask;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageErrorApiTest {
    static Stream<RuntimeException> storageFailures() {
        return Stream.of(new DataAccessResourceFailureException("private SQL and credential detail"),
                new CannotCreateTransactionException("private SQL and credential detail"),
                new TransactionSystemException("private SQL and credential detail"));
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void submissionStorageOutageReturnsSafe503WithoutScheduling(RuntimeException failure) throws Exception {
        var repository = new InMemoryTaskRepository() {
            @Override
            public Registration createOrGet(String key, String input) {
                throw failure;
            }
        };
        var service = new TaskService(repository, new MockModelClient(), runnable -> {
            throw new AssertionError("Must not dispatch after failed storage registration");
        });
        var mvc = MockMvcBuilders.standaloneSetup(new TaskController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"outage\",\"input\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("private SQL"))));
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void queryStorageOutageIsNotMisreportedAsMissingTask(RuntimeException failure) throws Exception {
        var repository = new InMemoryTaskRepository() {
            @Override
            public Optional<TextTask> findById(UUID id) {
                throw failure;
            }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new TaskController(
                new TaskService(repository, new MockModelClient(), runnable -> { })))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/tasks/{id}", UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    }
}
