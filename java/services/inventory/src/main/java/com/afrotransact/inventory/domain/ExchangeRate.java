package com.afrotransact.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * A staff-managed rate: on {@code rateDate} one unit of {@code currency} was worth {@code rateToUsd} US dollars. The
 * latest rate on or before the receiving date is suggested on receipts; the receiver may enter another.
 */
public record ExchangeRate(String currency, LocalDate rateDate, BigDecimal rateToUsd, String note, String updatedBy,
                           Instant createdAt, Instant updatedAt, long version) {

    public ExchangeRate {
        if (!UsdConversion.isCurrency(currency) || LotCost.USD.equals(currency)) {
            throw new InventoryRuleViolation("inventory.invalid-currency", "Rates are kept for currencies other than "
                    + "the US dollar", Map.of("currency", "A currency other than USD"));
        }
        if (rateDate == null) {
            throw new InventoryRuleViolation("inventory.invalid-request", "A rate is for a day",
                    Map.of("rateDate", "Required"));
        }
        requireUsable(rateToUsd);
        if (note != null && note.length() > 200) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Note is too long",
                    Map.of("note", "At most 200 characters"));
        }
    }

    /** The rate without trailing zeros and never in exponent form (0.0577, not 0.0577000000; 17, not 1.7E+1). */
    public static BigDecimal tidy(BigDecimal rate) {
        BigDecimal stripped = rate.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** A rate must be positive and have at most {@link LotCost#RATE_SCALE} decimals. */
    public static void requireUsable(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new InventoryRuleViolation("inventory.invalid-rate", "The exchange rate must be positive",
                    Map.of("rateToUsd", "Must be greater than 0"));
        }
        if (rate.stripTrailingZeros().scale() > LotCost.RATE_SCALE) {
            throw new InventoryRuleViolation("inventory.invalid-rate", "The exchange rate has too many decimals",
                    Map.of("rateToUsd", "At most " + LotCost.RATE_SCALE + " decimals"));
        }
    }
}
