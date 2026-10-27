package com.example.aitask.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** One script executes count + expiry atomically. No application clock or JVM-local counters. */
public final class RedisSubmissionRateLimiter implements SubmissionRateLimiter {
    private final StringRedisTemplate redis;
    private final String namespace;
    private final int maximum;
    private final long windowMs;
    private final DefaultRedisScript<List> script;

    public RedisSubmissionRateLimiter(StringRedisTemplate redis, String namespace, int maximum, long windowMs) {
        if (!namespace.matches("p1:[A-Za-z0-9:_-]{1,120}") || maximum < 1 || maximum > 100000
                || windowMs < 100 || windowMs > 86400000) {
            throw new IllegalArgumentException("Invalid P1 rate limit namespace, maximum or window");
        }
        this.redis = redis;
        this.namespace = namespace;
        this.maximum = maximum;
        this.windowMs = windowMs;
        this.script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/submission-window.lua"));
        script.setResultType(List.class);
    }

    public String keyFor(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) { throw RateLimitException.unavailable(); }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(remoteAddress.getBytes(StandardCharsets.UTF_8));
            return namespace + ":" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override
    public Decision acquire(String remoteAddress) {
        try {
            List<?> result = redis.execute(script, List.of(keyFor(remoteAddress)),
                    Integer.toString(maximum), Long.toString(windowMs));
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Number permitted)
                    || !(result.get(1) instanceof Number ttl) || ttl.longValue() < 0
                    || (permitted.longValue() != 0 && permitted.longValue() != 1)) {
                throw RateLimitException.unavailable();
            }
            return new Decision(permitted.longValue() == 1, Math.max(1, (ttl.longValue() + 999) / 1000));
        } catch (RuntimeException failure) {
            // Fail closed: never fall back to independent local counters or proceed to task registration.
            throw RateLimitException.unavailable();
        }
    }
}
