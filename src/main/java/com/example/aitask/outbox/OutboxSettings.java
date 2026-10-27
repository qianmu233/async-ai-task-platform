package com.example.aitask.outbox;

public record OutboxSettings(int batchSize, int maxAttempts, long leaseMillis,
                             long baseBackoffMillis, long maxBackoffMillis) {
    public OutboxSettings {
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 20
                || leaseMillis < 30000 || leaseMillis > 600000 || baseBackoffMillis < 100
                || baseBackoffMillis > 60000 || maxBackoffMillis < baseBackoffMillis || maxBackoffMillis > 3600000) {
            throw new IllegalArgumentException("Invalid bounded Outbox batch/attempt/lease/backoff settings");
        }
    }
    public long backoffMillis(int attempt) {
        return Math.min(maxBackoffMillis, baseBackoffMillis * (1L << Math.min(Math.max(0, attempt - 1), 20)));
    }
}
