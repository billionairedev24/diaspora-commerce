package com.afrotransact.trustops.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A service-level timer: started at {@code startedAt}, due at {@code dueAt}. It is {@link SlaState#AT_RISK} in the
 * last quarter of its window and {@link SlaState#BREACHED} from the due time on.
 */
public record Sla(Instant startedAt, Instant dueAt) {

    /** Share of the window left at which a timer turns amber. */
    public static final double AT_RISK_SHARE = 0.25;

    public Sla {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(dueAt, "dueAt");
        if (dueAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("SLA due time is before its start");
        }
    }

    public static Sla starting(Instant start, Duration window) {
        return new Sla(start, start.plus(window));
    }

    public boolean breached(Instant now) {
        return !now.isBefore(dueAt);
    }

    public SlaState state(Instant now) {
        if (breached(now)) {
            return SlaState.BREACHED;
        }
        long total = Duration.between(startedAt, dueAt).toMillis();
        long left = Duration.between(now, dueAt).toMillis();
        return left <= total * AT_RISK_SHARE ? SlaState.AT_RISK : SlaState.ON_TRACK;
    }

    /** Time left before the due time (zero once breached). */
    public Duration remaining(Instant now) {
        Duration left = Duration.between(now, dueAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** How long past due (zero while on time). */
    public Duration overdue(Instant now) {
        Duration late = Duration.between(dueAt, now);
        return late.isNegative() ? Duration.ZERO : late;
    }
}
