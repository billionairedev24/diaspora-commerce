package com.afrotransact.ai.adapter.out.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class InMemoryCallRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-12T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final InMemoryCallRateLimiter limiter = new InMemoryCallRateLimiter(clock);

    @Test
    void allowsUpToTheLimitThenAsksToWait() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.acquire("a", 3)).isZero();
        }
        assertThat(limiter.acquire("a", 3)).isBetween(1L, 60L);
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("a", 3);
        }
        assertThat(limiter.acquire("a", 3)).isPositive();
        assertThat(limiter.acquire("b", 3)).isZero();
    }

    @Test
    void theWindowSlides() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("a", 3);
        }
        now.set(now.get().plus(Duration.ofSeconds(61)));
        assertThat(limiter.acquire("a", 3)).isZero();
    }

    @Test
    void zeroMeansUnlimited() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(limiter.acquire("a", 0)).isZero();
        }
    }
}
