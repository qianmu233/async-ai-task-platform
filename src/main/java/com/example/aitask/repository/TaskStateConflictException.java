package com.example.aitask.repository;

import com.example.aitask.domain.TaskStatus;
import java.util.UUID;

/** A lost conditional update is a concurrency outcome, never a reason to overwrite another worker. */
public class TaskStateConflictException extends IllegalStateException {
    public TaskStateConflictException(UUID id, TaskStatus current, TaskStatus expected) {
        super("Task " + id + " is " + current + "; expected " + expected);
    }
}
