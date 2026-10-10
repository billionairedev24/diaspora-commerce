package com.afrotransact.taxreturns.domain.tax;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Combined local (county / city / special district) rate added to the state rate for a ZIP code or ZIP prefix.
 *
 * @param state            USPS state code the ZIPs belong to
 * @param zipPrefix        a full 5-digit ZIP (exact match) or a 3-digit prefix
 * @param name             jurisdiction name (e.g. Houston, TX)
 * @param rate             combined local rate as a fraction
 * @param appliesToGrocery whether the local rate also applies to grocery
 */
public record LocalTaxRate(String state, String zipPrefix, String name, BigDecimal rate, boolean appliesToGrocery) {

    public LocalTaxRate {
        Objects.requireNonNull(state, "state");
        if (zipPrefix == null || !zipPrefix.matches("\\d{3}|\\d{5}")) {
            throw new IllegalArgumentException("zipPrefix must be a 5-digit ZIP or a 3-digit prefix");
        }
        StateTaxRule.requireRate(rate, "rate");
    }

    /** Local rate for a category. */
    public BigDecimal rateFor(TaxCategory category) {
        return category == TaxCategory.GROCERY && !appliesToGrocery ? BigDecimal.ZERO : rate;
    }

    /** Local jurisdiction code, e.g. {@code US-TX-770}. */
    public String jurisdiction() {
        return "US-" + state + "-" + zipPrefix;
    }
}
