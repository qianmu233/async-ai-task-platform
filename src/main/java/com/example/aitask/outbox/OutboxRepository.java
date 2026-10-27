package com.example.aitask.outbox;

import java.util.UUID;

public interface OutboxRepository {
    String TASK_SUBMITTED = "TASK_SUBMITTED";
    record Claim(UUID eventId, UUID taskId, String eventType, int attempts, UUID token) { }
    /** Empty means no row or a lost CAS; isolated=true means an expired final attempt was quarantined. */
    record Acquisition(Claim claim, boolean isolated) {
        public static Acquisition empty() { return new Acquisition(null, false); }
    }
    Acquisition claim();
    boolean markSent(Claim claim);
    boolean markFailure(Claim claim, String code, long retryDelayMillis, boolean isolate);
}
