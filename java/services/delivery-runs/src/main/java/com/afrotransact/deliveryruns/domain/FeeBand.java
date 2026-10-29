package com.afrotransact.deliveryruns.domain;

import java.math.BigDecimal;

/**
 * A distance band of the AfroTransact delivery rule: addresses {@code fromMiles} (inclusive) up to {@code toMiles}
 * (exclusive) from the hub pay {@code feeMinor}.
 */
public record FeeBand(BigDecimal fromMiles, BigDecimal toMiles, long feeMinor) {

    /** Farthest a band may reach. */
    public static final BigDecimal MAX_MILES = BigDecimal.valueOf(500);

    public FeeBand {
        if (fromMiles == null || toMiles == null || fromMiles.signum() < 0 || toMiles.compareTo(fromMiles) <= 0
                || toMiles.compareTo(MAX_MILES) > 0) {
            throw new RuleViolation("delivery-runs.invalid-fee-band",
                    "A distance band runs from a distance to a farther one, at most " + MAX_MILES + " miles");
        }
        if (feeMinor < 0 || feeMinor > DeliveryFeeRule.MAX_FEE_MINOR) {
            throw new RuleViolation("delivery-runs.invalid-fee", "Fees are between 0 and 100.00");
        }
        fromMiles = fromMiles.signum() == 0 ? BigDecimal.ZERO : fromMiles.stripTrailingZeros();
        toMiles = toMiles.stripTrailingZeros();
    }

    public boolean covers(BigDecimal miles) {
        return miles.compareTo(fromMiles) >= 0 && miles.compareTo(toMiles) < 0;
    }

    /** "0–10 mi". */
    public String label() {
        return fromMiles.toPlainString() + "–" + toMiles.toPlainString() + " mi";
    }
}
