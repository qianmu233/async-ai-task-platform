package com.example.aitask.service;

import com.example.aitask.domain.TextTask;
import com.example.aitask.domain.TaskStatus;
import com.example.aitask.model.ModelClient;
import com.example.aitask.repository.TaskRepository;
import com.example.aitask.repository.TaskStateConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private final TaskRepository repository;
    private final TaskDispatcher dispatcher;

    @Autowired
    public TaskService(TaskRepository repository, TaskDispatcher dispatcher) {
        this.repository = repository;
        this.dispatcher = dispatcher;
    }

    /** Keep the local-mode constructor contract used by existing tests and callers. */
    public TaskService(TaskRepository repository, ModelClient model, Executor taskExecutor) {
        this(repository, new LocalTaskDispatcher(taskExecutor, new TaskWorker(repository, model)));
    }

    public TaskRepository.Registration submit(String key, String input) {
        input = TaskInputRules.normalizeInput(input);
        TaskInputRules.validate(key, input);
        TaskRepository.Registration registration = repository.createOrGet(key, input);
        if (dispatcher.shouldDispatch(registration)) {
            try {
                dispatcher.dispatch(registration.task().id());
            } catch (RejectedExecutionException ex) {
                try {
                    repository.fail(registration.task().id(), TaskStatus.PENDING, "EXECUTION_REJECTED");
                } catch (TaskStateConflictException conflict) {
                    log.warn("Task {} changed state before execution rejection was recorded", registration.task().id());
                }
                return new TaskRepository.Registration(get(registration.task().id()), true);
            }
        }
        return registration;
    }

    public TextTask get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new TaskException(
                TaskException.Kind.TASK_NOT_FOUND, "Task not found"));
    }

}
