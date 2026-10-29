package com.afrotransact.payments.domain;

import java.util.Locale;
import java.util.Map;

/**
 * What a line sells, which decides the take rate payments charges on it.
 * <ul>
 *   <li>{@link #PRODUCT}: products sold by sellers; AfroTransact charges a <b>commission</b> (4000 Commission
 *       revenue), the fee schedule's "product commission" section;</li>
 *   <li>{@link #SERVICE}: service bookings of providers; AfroTransact charges a <b>connection fee</b> (4050 Service
 *       connection fees), the fee schedule's {@code connectionFee} section.</li>
 * </ul>
 * Both are a configurable percentage of the line's items amount and come on top of the plan fee the seller or provider
 * subscribes to.
 */
public enum FeeKind {
    PRODUCT,
    SERVICE;

    /** Category that service bookings used before they carried their own kind (legacy compatibility). */
    public static final String LEGACY_SERVICES_CATEGORY = "services";

    /** The kind of a line: explicit, else SERVICE for the legacy {@code services} category, else PRODUCT. */
    public static FeeKind of(String raw, String category) {
        if (raw != null && !raw.isBlank()) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new PaymentsRuleViolation("payments.invalid-request", "kind must be PRODUCT or SERVICE",
                        Map.of("kind", "PRODUCT or SERVICE"));
            }
        }
        return category != null && LEGACY_SERVICES_CATEGORY.equalsIgnoreCase(category.trim()) ? SERVICE : PRODUCT;
    }
}
