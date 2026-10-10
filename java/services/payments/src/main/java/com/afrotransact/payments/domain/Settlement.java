package com.afrotransact.payments.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Settlement hold of one order: sellers' net becomes transferable {@code hold} after delivery or service completion
 * (7 days by default). Funds stay in AfroTransact's balance during the hold.
 */
public record Settlement(String orderId, SettlementKind kind, Instant deliveredAt, Instant releaseAt,
                         SettlementStatus status, Instant updatedAt) {

    public Settlement {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        Objects.requireNonNull(releaseAt, "releaseAt");
    }

    public static Settlement delivered(String orderId, SettlementKind kind, Instant deliveredAt, Duration hold,
                                       Instant now) {
        if (deliveredAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new PaymentsRuleViolation("payments.delivered-in-future", "deliveredAt cannot be in the future");
        }
        Instant at = deliveredAt.truncatedTo(ChronoUnit.MICROS);
        return new Settlement(orderId, kind, at, at.plus(hold), SettlementStatus.OPEN, now.truncatedTo(ChronoUnit.MICROS));
    }

    public boolean isMatured(Instant now) {
        return !releaseAt.isAfter(now);
    }
}
