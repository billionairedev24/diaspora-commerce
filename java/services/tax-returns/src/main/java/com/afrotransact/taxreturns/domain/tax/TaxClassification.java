package com.afrotransact.taxreturns.domain.tax;

/**
 * How an item was classified.
 *
 * @param taxCode  our code
 * @param category rates column
 * @param providerCode external provider's code, or null
 * @param basis    why this code
 */
public record TaxClassification(String taxCode, TaxCategory category, String providerCode, Basis basis) {

    /** Why the code was chosen. {@code DEFAULT} items are shown to staff for review. */
    public enum Basis {
        /** A rule matched the product type and the seller's answer. */
        ANSWER,
        /** A rule matched the product type alone. */
        TYPE,
        /** No rule matched (or the seller did not answer): the safe default, general taxable goods. */
        DEFAULT
    }

    public boolean needsReview() {
        return basis == Basis.DEFAULT;
    }
}
