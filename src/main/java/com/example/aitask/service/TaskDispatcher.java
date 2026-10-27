package com.example.aitask.service;

import com.example.aitask.repository.TaskRepository;
import java.util.UUID;

public interface TaskDispatcher {
    void dispatch(UUID taskId);
    default boolean shouldDispatch(TaskRepository.Registration registration) { return registration.created(); }
}
