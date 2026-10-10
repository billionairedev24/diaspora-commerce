package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class DefaultSignInThrottlePolicyTest {

    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");

    private final DefaultSignInThrottlePolicy policy = DefaultSignInThrottlePolicy.defaults();

    @Test
    void fifthAccountFailureWithinTheWindowLocksForFifteenMinutes() {
        ThrottleState s = ThrottleState.fresh(ThrottleScope.ACCOUNT, "ada@example.com", T0);
        for (int i = 0; i < 4; i++) {
            s = policy.onFailure(s, T0.plusSeconds(i));
            assertThat(policy.lockedFor(s, T0.plusSeconds(i))).isEmpty();
        }
        s = policy.onFailure(s, T0.plusSeconds(10));
        assertThat(policy.lockedFor(s, T0.plusSeconds(10))).contains(Duration.ofMinutes(15));
        assertThat(s.lockouts()).isEqualTo(1);
        assertThat(policy.lockedFor(s, T0.plusSeconds(10).plus(Duration.ofMinutes(15)))).isEmpty();
    }

    @Test
    void failuresOutsideTheWindowStartANewCount() {
        ThrottleState s = ThrottleState.fresh(ThrottleScope.ACCOUNT, "k", T0);
        for (int i = 0; i < 4; i++) {
            s = policy.onFailure(s, T0);
        }
        s = policy.onFailure(s, T0.plus(Duration.ofMinutes(16)));
        assertThat(s.failures()).isEqualTo(1);
        assertThat(s.lockedAt(T0.plus(Duration.ofMinutes(16)))).isFalse();
    }

    @Test
    void consecutiveLockoutsDoubleUpToTheCap() {
        ThrottleState s = ThrottleState.fresh(ThrottleScope.ACCOUNT, "k", T0);
        Instant now = T0;
        Duration[] expected = {Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(60),
            Duration.ofMinutes(120), Duration.ofMinutes(240), Duration.ofMinutes(240)};
        for (Duration lock : expected) {
            for (int i = 0; i < 5; i++) {
                s = policy.onFailure(s, now);
            }
            assertThat(policy.lockedFor(s, now)).contains(lock);
            now = s.lockedUntil();
        }
    }

    @Test
    void escalationIsForgottenAfterAQuietDay() {
        ThrottleState s = new ThrottleState(ThrottleScope.ACCOUNT, "k", 4, T0, T0.minus(Duration.ofDays(2)), 3);
        s = policy.onFailure(s, T0);
        assertThat(policy.lockedFor(s, T0)).contains(Duration.ofMinutes(15));
        assertThat(s.lockouts()).isEqualTo(1);
    }

    @Test
    void failuresWhileLockedDoNotExtendTheLock() {
        ThrottleState locked = new ThrottleState(ThrottleScope.IP, "203.0.113.7", 0, T0, T0.plusSeconds(60), 1);
        assertThat(policy.onFailure(locked, T0)).isEqualTo(locked);
    }

    @Test
    void successClearsTheAccountButNotTheIpCounter() {
        ThrottleState account = new ThrottleState(ThrottleScope.ACCOUNT, "k", 3, T0, null, 2);
        ThrottleState ip = new ThrottleState(ThrottleScope.IP, "203.0.113.7", 12, T0, null, 0);
        assertThat(policy.onSuccess(account, T0).failures()).isZero();
        assertThat(policy.onSuccess(account, T0).lockouts()).isZero();
        assertThat(policy.onSuccess(ip, T0)).isEqualTo(ip);
    }

    @Test
    void ipLimitIsHigherThanTheAccountLimit() {
        ThrottleState s = ThrottleState.fresh(ThrottleScope.IP, "203.0.113.7", T0);
        for (int i = 0; i < 29; i++) {
            s = policy.onFailure(s, T0);
        }
        assertThat(s.lockedAt(T0)).isFalse();
        assertThat(policy.onFailure(s, T0).lockedAt(T0)).isTrue();
    }

    @Test
    void rejectsNonsensicalLimits() {
        assertThatThrownBy(() -> new DefaultSignInThrottlePolicy.Limits(0, Duration.ofMinutes(1),
                Duration.ofMinutes(1), Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultSignInThrottlePolicy.Limits(3, Duration.ofMinutes(1),
                Duration.ofHours(2), Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
