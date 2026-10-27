package com.example.aitask.outbox;

import org.springframework.scheduling.annotation.Scheduled;

public final class OutboxPoller {
    private final OutboxDispatcher dispatcher;
    public OutboxPoller(OutboxDispatcher dispatcher) { this.dispatcher = dispatcher; }
    @Scheduled(fixedDelayString = "${platform.outbox.poll-interval-ms:1000}",
               initialDelayString = "${platform.outbox.poll-interval-ms:1000}")
    public void poll() { dispatcher.dispatchBatch(); }
}
