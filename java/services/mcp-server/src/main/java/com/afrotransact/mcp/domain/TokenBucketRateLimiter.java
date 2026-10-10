package com.afrotransact.mcp.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket per key (client + subject): {@code capacity} tokens, refilled continuously at
 * {@code refillPerMinute}. Buckets live in memory, so the limit is per pod: this is the fallback while Valkey (the
 * global limiter) is unreachable in {@code open} failure mode, and the limiter used with {@code store=memory}.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final long capacity;
    private final double refillPerNano;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(long capacity, long refillPerMinute, Clock clock) {
        if (capacity <= 0 || refillPerMinute <= 0) {
            throw new IllegalArgumentException("capacity and refill must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerMinute / (double) Duration.ofMinutes(1).toNanos();
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key) {
        long now = nanos();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.updatedAt) * refillPerNano);
            bucket.updatedAt = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new Decision(true, (long) Math.floor(bucket.tokens), Duration.ZERO);
            }
            long waitNanos = (long) Math.ceil((1.0 - bucket.tokens) / refillPerNano);
            return new Decision(false, 0, Duration.ofNanos(waitNanos));
        }
    }

    private long nanos() {
        Instant i = clock.instant();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }

    private static final class Bucket {

        private double tokens;
        private long updatedAt;

        Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }
}
