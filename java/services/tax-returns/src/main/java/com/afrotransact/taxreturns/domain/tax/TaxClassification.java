package com.afrotransact.taxreturns.domain.tax;

import java.util.Locale;
import java.util.Set;

/**
 * How an item was classified.
 *
 * @param taxCode      our code
 * @param category     rates column
 * @param providerCode external provider's code, or null
 * @param basis        why this code
 * @param taxable      false when the code is never taxed
 * @param exemptStates states where the code is known to be exempt
 */
public record TaxClassification(String taxCode, TaxCategory category, String providerCode, Basis basis,
                                boolean taxable, Set<String> exemptStates) {

    /** Why the code was chosen. {@code DEFAULT} items are shown to staff for review. */
    public enum Basis {
        /** A rule matched the product type and the seller's answer. */
        ANSWER,
        /** A rule matched the product type alone. */
        TYPE,
        /** No rule matched (or the seller did not answer): the safe default, general taxable goods. */
        DEFAULT
    }

    public TaxClassification(String taxCode, TaxCategory category, String providerCode, Basis basis) {
        this(taxCode, category, providerCode, basis, true, Set.of());
    }

    public boolean needsReview() {
        return basis == Basis.DEFAULT;
    }

    /** True when no tax is due for a delivery to {@code state}: known non-taxable, or known exempt there. */
    public boolean exemptIn(String state) {
        return !taxable || (state != null && exemptStates.contains(state.toUpperCase(Locale.ROOT)));
    }
}
