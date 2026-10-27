package com.example.aitask.service;

import com.example.aitask.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionException;
import java.util.UUID;

/** Conservative failure only: no model calls, renewal, retries, or task re-registration. */
public final class TaskTimeoutChecker {
    private static final Logger log = LoggerFactory.getLogger(TaskTimeoutChecker.class);
    private final TaskRepository repository;
    private final int batchSize;
    public TaskTimeoutChecker(TaskRepository repository, int batchSize) {
        TaskRepository.validateTimeoutBatch(batchSize);
        this.repository = repository; this.batchSize = batchSize;
    }
    public int check(UUID taskScope) { return repository.expireRunning(batchSize, taskScope); }
    @Scheduled(fixedDelayString="${platform.execution.timeout-check-ms:1000}",
            initialDelayString="${platform.execution.timeout-check-ms:1000}")
    public void poll() {
        try {
            int count = check(null);
            if (count > 0) { log.warn("TASK_TIMEOUT rows={}: expired executions failed without model retry", count); }
        } catch (DataAccessException | TransactionException unavailable) {
            log.error("TASK_TIMEOUT_STORAGE_UNAVAILABLE type={}: no success assumed", unavailable.getClass().getSimpleName());
        } catch (RuntimeException unexpected) {
            log.error("TASK_TIMEOUT_CHECK_UNEXPECTED type={}: next fixed-delay check will revisit", unexpected.getClass().getSimpleName());
        }
    }
}
