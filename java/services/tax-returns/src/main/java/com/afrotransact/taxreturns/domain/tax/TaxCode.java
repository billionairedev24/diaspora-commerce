package com.afrotransact.taxreturns.domain.tax;

/**
 * A tax code of ours: what an item is for tax. {@code category} is the rate column the rules engine uses;
 * {@code providerCode} is the matching code of an external tax provider (Stripe {@code txcd_...}), null until staff
 * enter it.
 *
 * @param code         stable code (GENERAL_GOODS, CLOTHING, PACKAGED_FOOD ...)
 * @param label        what staff see
 * @param category     rates column of the rules engine
 * @param providerCode external provider's code, or null
 * @param active       inactive codes are kept for history but not assigned
 */
public record TaxCode(String code, String label, TaxCategory category, String providerCode, boolean active) {

    /** The code every unclassified item gets: taxable tangible goods. */
    public static final String GENERAL_GOODS = "GENERAL_GOODS";

    public TaxCode {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        category = category == null ? TaxCategory.GENERAL : category;
    }
}
