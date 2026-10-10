package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.List;

/** The fields of payments-service's {@code payments.refunded} event the tax ledger needs. */
public record PaymentRefunded(String orderId, String refundId, String currency, String status, Instant occurredAt,
                              List<Line> lines) {

    public PaymentRefunded {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public record Line(String lineId, long itemsMinor, long taxMinor) {
    }
}
