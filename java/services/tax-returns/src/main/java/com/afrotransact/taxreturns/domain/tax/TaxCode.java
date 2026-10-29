package com.afrotransact.taxreturns.domain.tax;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
 * @param taxable      false = never taxed anywhere: no tax is charged and the provider is not called
 * @param exemptStates states (two-letter codes) where the code is known to be exempt: no tax is charged there and the
 *                     provider is not called
 */
public record TaxCode(String code, String label, TaxCategory category, String providerCode, boolean active,
                      boolean taxable, Set<String> exemptStates) {

    /** The code every unclassified item gets: taxable tangible goods. */
    public static final String GENERAL_GOODS = "GENERAL_GOODS";

    public TaxCode {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        category = category == null ? TaxCategory.GENERAL : category;
        exemptStates = exemptStates == null ? Set.of() : Set.copyOf(exemptStates);
    }

    public TaxCode(String code, String label, TaxCategory category, String providerCode, boolean active) {
        this(code, label, category, providerCode, active, true, Set.of());
    }

    /** Parses "tx, NJ" into {TX, NJ}. */
    public static Set<String> parseStates(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split("[,\\s]+")).map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
