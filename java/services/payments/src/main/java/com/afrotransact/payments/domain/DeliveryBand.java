package com.afrotransact.payments.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * A distance band of a {@link DeliveryMode#DISTANCE} tariff: trips of {@code fromMiles} (inclusive) up to
 * {@code toMiles} (exclusive) cost {@code fee}, instead of base + per mile.
 */
public record DeliveryBand(BigDecimal fromMiles, BigDecimal toMiles, Money fee) {

    public DeliveryBand {
        Objects.requireNonNull(fromMiles, "fromMiles");
        Objects.requireNonNull(toMiles, "toMiles");
        Objects.requireNonNull(fee, "fee");
        if (fromMiles.signum() < 0 || toMiles.compareTo(fromMiles) <= 0) {
            throw new PaymentsRuleViolation("payments.invalid-delivery-pricing", "A distance band needs 0 ≤ from < to "
                    + "(" + fromMiles + "–" + toMiles + " mi)", Map.of("bands", "0 ≤ fromMiles < toMiles"));
        }
        if (fee.isNegative()) {
            throw new PaymentsRuleViolation("payments.invalid-delivery-pricing", "Band fees cannot be negative",
                    Map.of("bands", "Fee must be ≥ 0"));
        }
    }

    boolean covers(BigDecimal miles) {
        return miles.compareTo(fromMiles) >= 0 && miles.compareTo(toMiles) < 0;
    }
}
