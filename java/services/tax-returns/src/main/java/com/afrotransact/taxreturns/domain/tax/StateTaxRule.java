package com.afrotransact.taxreturns.domain.tax;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * State-level sales tax rule. Rates are decimal fractions (0.0625 = 6.25 %).
 *
 * @param country          ISO 3166-1 alpha-2 (US)
 * @param state            USPS state code (TX)
 * @param name             display name
 * @param generalRate      state rate for {@link TaxCategory#GENERAL}
 * @param groceryRate      state rate for {@link TaxCategory#GROCERY} (0 = exempt)
 * @param preparedFoodRate state rate for {@link TaxCategory#PREPARED_FOOD}
 * @param shippingTaxable  whether delivery charges follow the item's taxability
 * @param facilitator      true when AfroTransact must collect and remit as marketplace facilitator
 * @param version          optimistic-lock version
 */
public record StateTaxRule(String country, String state, String name, BigDecimal generalRate, BigDecimal groceryRate,
                           BigDecimal preparedFoodRate, boolean shippingTaxable, boolean facilitator, long version) {

    public StateTaxRule {
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(state, "state");
        requireRate(generalRate, "generalRate");
        requireRate(groceryRate, "groceryRate");
        requireRate(preparedFoodRate, "preparedFoodRate");
    }

    /** State rate for a category. */
    public BigDecimal rateFor(TaxCategory category) {
        return switch (category) {
            case GROCERY -> groceryRate;
            case PREPARED_FOOD -> preparedFoodRate;
            case GENERAL -> generalRate;
        };
    }

    /** State jurisdiction code, e.g. {@code US-TX}. */
    public String jurisdiction() {
        return country + "-" + state;
    }

    static void requireRate(BigDecimal rate, String field) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(field + " must be a fraction between 0 and 1");
        }
    }
}
