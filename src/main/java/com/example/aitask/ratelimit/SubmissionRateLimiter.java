package com.example.aitask.ratelimit;

public interface SubmissionRateLimiter {
    record Decision(boolean allowed, long retryAfterSeconds) { }
    Decision acquire(String remoteAddress);
}
