package com.afrotransact.seller.domain;

import java.util.Locale;
import java.util.Map;

/**
 * How an organisation pays its plan (S298).
 */
public enum PlanFeeCollection {
    /**
     * "Pay your plan from your sales": each plan invoice is taken from pending earnings (payments withholds it from the
     * next transfers) and the card pays only what earnings don't cover.
     */
    EARNINGS_FIRST,
    /** "Charge my card": plan fees stay a separate card expense, e.g. for the seller's own accounting. */
    CARD;

    /** Parses a request value; 422 with a field error when it is neither. */
    public static PlanFeeCollection parse(String value, String field) {
        if (value != null) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // falls through to the validation error
            }
        }
        throw new SellerRuleViolation("seller.invalid-plan-fee-collection", "Choose EARNINGS_FIRST or CARD",
                Map.of(field, "EARNINGS_FIRST or CARD"));
    }
}
