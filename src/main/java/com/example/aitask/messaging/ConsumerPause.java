package com.example.aitask.messaging;

@FunctionalInterface
public interface ConsumerPause {
    /** Stop the container asynchronously. Unacknowledged messages remain broker-owned. */
    void pause();
}
