package com.afrotransact.payments.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Negotiated commission for one seller, for one category or for every category ({@code *}).
 *
 * @param commissionBps basis points (1000 = 10 %)
 */
public record CommissionOverride(String orgId, String category, int commissionBps) {

    public static final String ANY = "*";

    public CommissionOverride {
        Objects.requireNonNull(orgId, "orgId");
        category = category == null || category.isBlank() ? ANY : category.trim().toLowerCase(Locale.ROOT);
        FeeSchedule.requireBps(commissionBps, "override");
    }
}
