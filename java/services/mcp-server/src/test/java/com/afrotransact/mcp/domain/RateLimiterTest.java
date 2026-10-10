package com.afrotransact.mcp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void allowsTheBurstThenRefillsOverTime() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 60, clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        RateLimiter.Decision third = limiter.tryAcquire("a");
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();
        RateLimiter.Decision denied = limiter.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isEqualTo(Duration.ofSeconds(1));

        clock.advance(Duration.ofMillis(999));
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        clock.advance(Duration.ofMillis(2));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void bucketsAreIndependentPerKeyAndCapped() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 60, clock);
        limiter.tryAcquire("client-a|user");
        limiter.tryAcquire("client-a|user");
        assertThat(limiter.tryAcquire("client-a|user").allowed()).isFalse();
        assertThat(limiter.tryAcquire("client-b|user").allowed()).isTrue();

        clock.advance(Duration.ofHours(1));
        assertThat(limiter.tryAcquire("client-a|user").remaining()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveSettings() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 10, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-10T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
