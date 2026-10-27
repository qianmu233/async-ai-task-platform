package com.example.aitask.ratelimit;

/** Deliberately excludes connection details and Redis exception causes from API/log output. */
public final class RateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    private RateLimitException(long retryAfterSeconds) {
        super(retryAfterSeconds > 0 ? "Task submission rate exceeded" : "Submission rate limiter unavailable");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static RateLimitException exceeded(long seconds) { return new RateLimitException(Math.max(1, seconds)); }
    public static RateLimitException unavailable() { return new RateLimitException(0); }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
