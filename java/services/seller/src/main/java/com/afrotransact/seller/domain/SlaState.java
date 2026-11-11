package com.afrotransact.seller.domain;

import java.time.Duration;
import java.time.Instant;

/** Where a case stands against its stage's service level. */
public enum SlaState {
    ON_TIME,
    DUE_SOON,
    OVERDUE;

    /** Due soon once {@code dueSoonPercent} of the allowed time has passed. */
    public static SlaState of(Instant stageEnteredAt, Instant now, Duration allowed, int dueSoonPercent) {
        Duration waited = Duration.between(stageEnteredAt, now);
        if (waited.compareTo(allowed) >= 0) {
            return OVERDUE;
        }
        return waited.toMillis() * 100 >= allowed.toMillis() * dueSoonPercent ? DUE_SOON : ON_TIME;
    }
}
