package com.example.aitask.outbox;

import com.example.aitask.repository.TaskRepository;
import com.example.aitask.service.TaskDispatcher;
import java.util.UUID;

/** The JDBC registration already recorded the event. HTTP must not do a second MQ write. */
public final class OutboxSubmissionDispatcher implements TaskDispatcher {
    @Override public boolean shouldDispatch(TaskRepository.Registration registration) { return false; }
    @Override public void dispatch(UUID id) { throw new IllegalStateException("Outbox dispatch is asynchronous"); }
}
