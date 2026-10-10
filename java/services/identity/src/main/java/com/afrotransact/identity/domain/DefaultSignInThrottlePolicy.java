package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Fixed-window counters with escalating lockouts: {@code maxFailures} failures within {@code window} lock the key for
 * {@code lockout}, doubled for every consecutive lockout up to {@code maxLockout}. The escalation is forgotten after a
 * day without lockouts.
 */
public final class DefaultSignInThrottlePolicy implements SignInThrottlePolicy {

    /** Lockout escalation is forgotten after this long without a new lockout. */
    static final Duration ESCALATION_MEMORY = Duration.ofDays(1);

    private static final int MAX_DOUBLINGS = 16;

    private final Limits account;
    private final Limits ip;

    public DefaultSignInThrottlePolicy(Limits account, Limits ip) {
        this.account = account;
        this.ip = ip;
    }

    /** Production defaults: 5 failures per account / 30 per IP within 15 minutes, 15-minute lockouts up to 4 hours. */
    public static DefaultSignInThrottlePolicy defaults() {
        return new DefaultSignInThrottlePolicy(
                new Limits(5, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(4)),
                new Limits(30, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(4)));
    }

    /**
     * @param maxFailures failures that trigger a lockout
     * @param window      counting window
     * @param lockout     first lockout duration
     * @param maxLockout  cap for escalated lockouts
     */
    public record Limits(int maxFailures, Duration window, Duration lockout, Duration maxLockout) {

        public Limits {
            if (maxFailures < 1) {
                throw new IllegalArgumentException("maxFailures must be at least 1");
            }
            if (window == null || lockout == null || maxLockout == null || lockout.compareTo(maxLockout) > 0) {
                throw new IllegalArgumentException("window, lockout and maxLockout are required; lockout <= maxLockout");
            }
        }
    }

    public Limits limits(ThrottleScope scope) {
        return scope == ThrottleScope.ACCOUNT ? account : ip;
    }

    @Override
    public Optional<Duration> lockedFor(ThrottleState state, Instant now) {
        return state.lockedAt(now) ? Optional.of(Duration.between(now, state.lockedUntil())) : Optional.empty();
    }

    @Override
    public ThrottleState onFailure(ThrottleState state, Instant now) {
        if (state.lockedAt(now)) {
            return state;
        }
        Limits limits = limits(state.scope());
        int lockouts = state.lockouts();
        if (state.lockedUntil() != null && state.lockedUntil().plus(ESCALATION_MEMORY).isBefore(now)) {
            lockouts = 0;
        }
        boolean windowOver = !state.windowStartedAt().plus(limits.window()).isAfter(now);
        int failures = windowOver ? 1 : state.failures() + 1;
        Instant windowStart = windowOver ? now : state.windowStartedAt();
        if (failures < limits.maxFailures()) {
            return new ThrottleState(state.scope(), state.key(), failures, windowStart, state.lockedUntil(), lockouts);
        }
        Duration lock = escalated(limits, lockouts);
        return new ThrottleState(state.scope(), state.key(), 0, now, now.plus(lock), lockouts + 1);
    }

    @Override
    public ThrottleState onSuccess(ThrottleState state, Instant now) {
        if (state.scope() == ThrottleScope.IP) {
            return state;
        }
        return ThrottleState.fresh(state.scope(), state.key(), now);
    }

    private static Duration escalated(Limits limits, int previousLockouts) {
        Duration lock = limits.lockout();
        for (int i = 0; i < Math.min(previousLockouts, MAX_DOUBLINGS) && lock.compareTo(limits.maxLockout()) < 0; i++) {
            lock = lock.multipliedBy(2);
        }
        return lock.compareTo(limits.maxLockout()) > 0 ? limits.maxLockout() : lock;
    }
}
