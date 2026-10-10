package com.afrotransact.mcp.adapter.out.valkey;

import java.time.Duration;
import java.util.List;

import com.afrotransact.mcp.domain.RateLimitFailureMode;
import com.afrotransact.mcp.domain.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Global token bucket per key (OAuth client + subject) in Valkey, shared by every replica. One atomic Lua script
 * ({@code valkey/token-bucket.lua}) refills and takes a token, using the Valkey server clock. When Valkey cannot be
 * reached the {@link RateLimitFailureMode} applies: {@code OPEN} degrades to the given per-pod fallback limiter,
 * {@code CLOSED} refuses the call.
 */
public final class ValkeyTokenBucketRateLimiter implements RateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(ValkeyTokenBucketRateLimiter.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(new ClassPathResource("valkey/token-bucket.lua"),
            List.class);
    private static final Duration CLOSED_RETRY = Duration.ofSeconds(1);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final String capacity;
    private final String refillPerMs;
    private final RateLimitFailureMode failureMode;
    private final RateLimiter fallback;

    /**
     * @param redis           Valkey connection
     * @param keyPrefix       prefix of the bucket keys, e.g. {@code mcp:rl:}
     * @param capacity        burst size
     * @param refillPerMinute tokens added per minute
     * @param failureMode     behaviour when Valkey is unavailable
     * @param fallback        per-pod limiter used in {@code OPEN} mode while Valkey is down
     */
    public ValkeyTokenBucketRateLimiter(StringRedisTemplate redis, String keyPrefix, long capacity,
                                        long refillPerMinute, RateLimitFailureMode failureMode,
                                        RateLimiter fallback) {
        if (capacity <= 0 || refillPerMinute <= 0) {
            throw new IllegalArgumentException("capacity and refill must be positive");
        }
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.capacity = Long.toString(capacity);
        this.refillPerMs = Double.toString(refillPerMinute / (double) Duration.ofMinutes(1).toMillis());
        this.failureMode = failureMode;
        this.fallback = fallback;
    }

    @Override
    public Decision tryAcquire(String key) {
        List<?> result;
        try {
            result = redis.execute(SCRIPT, List.of(keyPrefix + key), capacity, refillPerMs);
        } catch (DataAccessException e) {
            return unavailable(key, e);
        }
        if (result == null || result.size() < 3) {
            return unavailable(key, null);
        }
        boolean allowed = ((Number) result.get(0)).longValue() == 1L;
        long remaining = ((Number) result.get(1)).longValue();
        Duration retryAfter = Duration.ofMillis(((Number) result.get(2)).longValue());
        return new Decision(allowed, remaining, allowed ? Duration.ZERO : retryAfter);
    }

    private Decision unavailable(String key, RuntimeException cause) {
        LOG.warn("Valkey rate limiter unavailable ({}); failing {}",
                cause == null ? "empty reply" : cause.getClass().getSimpleName(), failureMode);
        if (failureMode == RateLimitFailureMode.CLOSED) {
            return new Decision(false, 0, CLOSED_RETRY);
        }
        return fallback.tryAcquire(key);
    }
}
