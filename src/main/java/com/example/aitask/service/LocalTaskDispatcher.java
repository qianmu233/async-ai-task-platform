package com.example.aitask.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.concurrent.Executor;

public class LocalTaskDispatcher implements TaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(LocalTaskDispatcher.class);
    private final Executor executor;
    private final TaskWorker worker;

    public LocalTaskDispatcher(Executor executor, TaskWorker worker) {
        this.executor = executor;
        this.worker = worker;
    }

    @Override
    public void dispatch(UUID id) {
        executor.execute(() -> {
            try { worker.execute(id); }
            catch (TaskWorker.StorageFailure failure) {
                log.error("Task {} storage failure during {}", id, failure.stage());
            }
        });
    }
}
