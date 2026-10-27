package com.example.aitask.domain;

public enum TaskStatus {
    PENDING, RUNNING, SUCCESS, FAILED;

    public boolean canTransitionTo(TaskStatus next) {
        return switch (this) {
            case PENDING -> next == RUNNING || next == FAILED;
            case RUNNING -> next == SUCCESS || next == FAILED;
            case SUCCESS, FAILED -> false;
        };
    }

    public void requireTransitionTo(TaskStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Illegal task transition: " + this + " -> " + next);
        }
    }
}
