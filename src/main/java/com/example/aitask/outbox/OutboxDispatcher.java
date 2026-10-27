package com.example.aitask.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import java.util.UUID;

public final class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    @FunctionalInterface
    public interface Publisher { void publish(UUID eventId, UUID taskId); }
    public record Result(int claimed, int confirmed, int markedSent, int publicationFailures,
                         int isolated, int ownershipLost, int storageFailures, int unexpectedFailures) { }
    private final OutboxRepository repository;
    private final Publisher publisher;
    private final OutboxSettings settings;

    public OutboxDispatcher(OutboxRepository repository, Publisher publisher, OutboxSettings settings) {
        this.repository = repository;
        this.publisher = publisher;
        this.settings = settings;
    }

    public Result dispatchBatch() {
        int claimed = 0, confirmed = 0, sent = 0, failures = 0, isolated = 0, lost = 0, storage = 0, unexpected = 0;
        for (int i = 0; i < settings.batchSize() && !Thread.currentThread().isInterrupted(); i++) {
            try {
                var acquisition = repository.claim(); // Short transaction ends before publication.
                if (acquisition.isolated()) { isolated++; continue; }
                var event = acquisition.claim();
                if (event == null) { break; } // Lost CAS/no due work: next fixed-delay poll, no hot loop.
                claimed++;
                if (!OutboxRepository.TASK_SUBMITTED.equals(event.eventType())) {
                    if (repository.markFailure(event, "INVALID_EVENT_TYPE", 0, true)) { isolated++; }
                    else { lost++; }
                    continue;
                }
                boolean publicationSucceeded;
                try {
                    publisher.publish(event.eventId(), event.taskId());
                    publicationSucceeded = true;
                    confirmed++;
                } catch (RuntimeException notConfirmed) {
                    publicationSucceeded = false;
                    failures++;
                }
                if (publicationSucceeded) {
                    // If this commit fails/has unknown outcome, leave the lease for expiry. Never mark retry on that assumption.
                    if (repository.markSent(event)) { sent++; } else { lost++; }
                } else {
                    boolean exhausted = event.attempts() >= settings.maxAttempts();
                    if (repository.markFailure(event, "PUBLISH_UNCONFIRMED", settings.backoffMillis(event.attempts()), exhausted)) {
                        if (exhausted) { isolated++; }
                    } else { lost++; }
                }
                log.info("OUTBOX event={} task={} attempt={} confirmed={} type=TASK_SUBMITTED",
                        event.eventId(), event.taskId(), event.attempts(), publicationSucceeded);
            } catch (DataAccessException | TransactionException unavailable) {
                storage++;
                log.error("OUTBOX_STORAGE_UNAVAILABLE type={}: batch stopped; persisted event/claim will be revisited",
                        unavailable.getClass().getSimpleName());
                break;
            } catch (RuntimeException unexpectedFailure) {
                unexpected++; // No success claim; an unresolved persisted claim remains eligible after expiry.
                log.error("OUTBOX_DISPATCH_UNEXPECTED: batch stopped; unresolved event retained");
                break;
            }
        }
        return new Result(claimed, confirmed, sent, failures, isolated, lost, storage, unexpected);
    }
}
