package com.afrotransact.mcp.adapter.out.valkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.mcp.domain.RateLimitFailureMode;
import com.afrotransact.mcp.domain.RateLimiter;
import com.afrotransact.mcp.domain.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * S207 load test: three limiter instances (three "replicas", each with its own Valkey connection) share one Valkey.
 * A concurrent burst far above the capacity from all three must be admitted exactly {@code capacity} times in total,
 * where per-pod buckets would have admitted three times as many.
 */
class GlobalRateLimitIT {

    private static final int CAPACITY = 30;
    private static final int CALLS_PER_REPLICA = 100;

    @Test
    void limitHoldsGloballyAcrossThreeReplicas() throws Exception {
        List<RateLimiter> replicas = List.of(limiter(ValkeyTestSupport.template()),
                limiter(ValkeyTestSupport.template()), limiter(ValkeyTestSupport.template()));
        String key = "client-a|" + UUID.randomUUID();

        int admitted = hammer(replicas, key);

        assertThat(admitted).isEqualTo(CAPACITY);
        RateLimiter.Decision denied = replicas.getFirst().tryAcquire(key);
        assertThat(denied.allowed()).isFalse();
        // 1 token per 2 s (30 per minute): the next token is at most 2 s away
        assertThat(denied.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void perPodBucketsWouldHaveLetThreeTimesAsManyThrough() throws Exception {
        List<RateLimiter> pods = List.of(
                new TokenBucketRateLimiter(CAPACITY, CAPACITY, Clock.systemUTC()),
                new TokenBucketRateLimiter(CAPACITY, CAPACITY, Clock.systemUTC()),
                new TokenBucketRateLimiter(CAPACITY, CAPACITY, Clock.systemUTC()));

        assertThat(hammer(pods, "client-a|subject")).isEqualTo(3 * CAPACITY);
    }

    @Test
    void bucketsAreIndependentPerKeyAndRefill() throws Exception {
        StringRedisTemplate redis = ValkeyTestSupport.template();
        RateLimiter fast = new ValkeyTokenBucketRateLimiter(redis, "test:rl:", 2, 600, RateLimitFailureMode.CLOSED,
                new TokenBucketRateLimiter(2, 600, Clock.systemUTC()));
        String a = "a|" + UUID.randomUUID();
        String b = "b|" + UUID.randomUUID();
        assertThat(fast.tryAcquire(a).allowed()).isTrue();
        assertThat(fast.tryAcquire(a).remaining()).isZero();
        assertThat(fast.tryAcquire(a).allowed()).isFalse();
        assertThat(fast.tryAcquire(b).allowed()).isTrue();

        Thread.sleep(250); // 600/min = 1 token per 100 ms
        assertThat(fast.tryAcquire(a).allowed()).isTrue();
        assertThat(redis.getExpire("test:rl:" + a)).isPositive();
    }

    @Test
    void failureModeAppliesWhenValkeyIsUnreachable() {
        StringRedisTemplate dead = ValkeyTestSupport.template("localhost", 1);
        RateLimiter closed = new ValkeyTokenBucketRateLimiter(dead, "test:rl:", 5, 60, RateLimitFailureMode.CLOSED,
                new TokenBucketRateLimiter(5, 60, Clock.systemUTC()));
        RateLimiter.Decision refused = closed.tryAcquire("k");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isPositive();

        RateLimiter open = new ValkeyTokenBucketRateLimiter(dead, "test:rl:", 2, 60, RateLimitFailureMode.OPEN,
                new TokenBucketRateLimiter(2, 60, Clock.systemUTC()));
        assertThat(open.tryAcquire("k").allowed()).isTrue();
        assertThat(open.tryAcquire("k").allowed()).isTrue();
        assertThat(open.tryAcquire("k").allowed()).as("still bounded by the per-pod fallback").isFalse();
    }

    private static RateLimiter limiter(StringRedisTemplate redis) {
        return new ValkeyTokenBucketRateLimiter(redis, "test:rl:", CAPACITY, CAPACITY, RateLimitFailureMode.CLOSED,
                new TokenBucketRateLimiter(CAPACITY, CAPACITY, Clock.systemUTC()));
    }

    /** Fires {@link #CALLS_PER_REPLICA} concurrent acquires on each replica at once; returns how many were admitted. */
    private static int hammer(List<RateLimiter> replicas, String key) throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RateLimiter replica : replicas) {
                for (int i = 0; i < CALLS_PER_REPLICA; i++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        if (replica.tryAcquire(key).allowed()) {
                            admitted.incrementAndGet();
                        }
                        return null;
                    }));
                }
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get();
            }
        }
        return admitted.get();
    }
}
