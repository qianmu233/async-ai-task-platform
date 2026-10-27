package com.example.aitask.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Minimal in-process repository double. Tests dispatcher decisions, NOT MySQL concurrency or lock semantics. */
class OutboxDispatcherTest {
    private final OutboxSettings settings = new OutboxSettings(4, 2, 60000, 100, 1000);

    static class Events implements OutboxRepository {
        final UUID event = UUID.randomUUID(), task = UUID.randomUUID();
        String status = "PENDING", type = TASK_SUBMITTED;
        int attempts;
        long now, due, expiry;
        UUID token;
        boolean failSent, failClaim;
        String lastError;
        @Override public synchronized Acquisition claim() {
            if (failClaim) { throw new DataAccessResourceFailureException("synthetic claim failure"); }
            if (status.equals("SENT") || status.equals("ISOLATED") || now < due
                    || status.equals("DISPATCHING") && now < expiry) { return Acquisition.empty(); }
            if (attempts >= 2) { status = "ISOLATED"; return new Acquisition(null, true); }
            attempts++;
            status = "DISPATCHING";
            expiry = now + 60000;
            token = UUID.randomUUID();
            return new Acquisition(new Claim(event, task, type, attempts, token), false);
        }
        private boolean owned(Claim claim) { return status.equals("DISPATCHING") && token.equals(claim.token()) && now < expiry; }
        @Override public synchronized boolean markSent(Claim claim) {
            if (failSent) { throw new DataAccessResourceFailureException("synthetic commit failure"); }
            if (!owned(claim)) { return false; }
            status = "SENT"; return true;
        }
        @Override public synchronized boolean markFailure(Claim claim, String code, long delay, boolean isolate) {
            if (!owned(claim)) { return false; }
            status = isolate ? "ISOLATED" : "RETRY"; due = now + delay; lastError = code; return true;
        }
    }

    @Test
    void confirmedEventIsMarkedSentAndSubsequentPollDoesNotRepublish() {
        var events = new Events();
        var calls = new AtomicInteger();
        var dispatcher = new OutboxDispatcher(events, (event, task) -> calls.incrementAndGet(), settings);
        var result = dispatcher.dispatchBatch();
        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(result.markedSent()).isEqualTo(1);
        assertThat(dispatcher.dispatchBatch().claimed()).isZero();
        assertThat(calls).hasValue(1);
    }

    @Test
    void brokerFailureBacksOffThenIsolatesAtBudgetWithoutAnImmediateRetryLoop() {
        var events = new Events();
        var calls = new AtomicInteger();
        var dispatcher = new OutboxDispatcher(events, (event, task) -> { calls.incrementAndGet(); throw new IllegalStateException(); }, settings);
        var first = dispatcher.dispatchBatch();
        assertThat(first.publicationFailures()).isEqualTo(1);
        assertThat(first.markedSent()).isZero();
        assertThat(events.status).isEqualTo("RETRY");
        assertThat(dispatcher.dispatchBatch().claimed()).isZero();
        events.now = 100;
        assertThat(dispatcher.dispatchBatch().isolated()).isEqualTo(1);
        assertThat(events.status).isEqualTo("ISOLATED");
        assertThat(events.lastError).isEqualTo("PUBLISH_UNCONFIRMED");
        events.now += 100000;
        assertThat(dispatcher.dispatchBatch().claimed()).isZero();
        assertThat(calls).hasValue(2);
    }

    @Test
    void confirmThenStorageFailureRetainsClaimAndCanRepublishAfterExpiry() {
        var events = new Events();
        events.failSent = true;
        var calls = new AtomicInteger();
        var dispatcher = new OutboxDispatcher(events, (event, task) -> calls.incrementAndGet(), settings);
        var result = dispatcher.dispatchBatch();
        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(result.markedSent()).isZero();
        assertThat(result.storageFailures()).isEqualTo(1);
        assertThat(events.status).isEqualTo("DISPATCHING");
        assertThat(dispatcher.dispatchBatch().claimed()).isZero();
        events.now = 60000;
        events.failSent = false;
        assertThat(dispatcher.dispatchBatch().markedSent()).isEqualTo(1);
        assertThat(calls).hasValue(2); // At-least-once publication; not twice successful model execution.
    }

    @Test
    void twoDispatchersCannotConcurrentlyPublishOneUnexpiredClaim() throws Exception {
        var events = new Events();
        var calls = new AtomicInteger();
        var publishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var dispatcherA = new OutboxDispatcher(events, (event, task) -> {
            calls.incrementAndGet(); publishing.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException(); } }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
        }, settings);
        var dispatcherB = new OutboxDispatcher(events, (event, task) -> calls.incrementAndGet(), settings);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<OutboxDispatcher.Result> a = pool.submit(dispatcherA::dispatchBatch);
            assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcherB.dispatchBatch().claimed()).isZero();
            release.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS).markedSent()).isEqualTo(1);
            assertThat(calls).hasValue(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void staleOwnerCannotCompleteAndAbandonedFinalAttemptIsIsolated() {
        var events = new Events();
        var stale = events.claim().claim();
        events.now = 60000;
        var current = events.claim().claim();
        assertThat(events.markSent(stale)).isFalse();
        assertThat(current.token()).isNotEqualTo(stale.token());
        events.now = 120000;
        var dispatcher = new OutboxDispatcher(events, (event, task) -> { throw new AssertionError("No publish at exhausted budget"); }, settings);
        assertThat(dispatcher.dispatchBatch().isolated()).isEqualTo(1);
        assertThat(events.status).isEqualTo("ISOLATED");
    }

    @Test
    void badEventIsIsolatedWithoutPublicationAndClaimStorageErrorCannotBeSuccess() {
        var events = new Events();
        events.type = "unsupported";
        var dispatcher = new OutboxDispatcher(events, (event, task) -> { throw new AssertionError(); }, settings);
        assertThat(dispatcher.dispatchBatch().isolated()).isEqualTo(1);
        assertThat(events.lastError).isEqualTo("INVALID_EVENT_TYPE");
        events.failClaim = true;
        var failure = dispatcher.dispatchBatch();
        assertThat(failure.storageFailures()).isEqualTo(1);
        assertThat(failure.confirmed()).isZero();
        assertThat(failure.markedSent()).isZero();
    }
}
