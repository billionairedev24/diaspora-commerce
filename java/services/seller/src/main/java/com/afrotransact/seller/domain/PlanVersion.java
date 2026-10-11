package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable prices (per cadence) and default trial of a plan. Changing either creates the next version; subscribers
 * stay on the version they subscribed to until staff migrate them.
 */
public record PlanVersion(String planCode, int version, PlanVersionStatus status, Map<Cadence, Long> prices,
                          int trialDays, String note, String createdBy, Instant createdAt, Instant publishedAt) {

    public PlanVersion {
        Objects.requireNonNull(planCode, "planCode");
        prices = prices == null ? Map.of() : Map.copyOf(prices);
    }

    public long price(Cadence cadence) {
        Long p = prices.get(cadence);
        return p == null ? 0 : p;
    }

    public boolean isFree() {
        return prices.values().stream().allMatch(p -> p == 0);
    }

    /** Whether these prices and trial differ from {@code other} (so saving them needs a new version). */
    public boolean differsFrom(Map<Cadence, Long> otherPrices, int otherTrialDays) {
        if (trialDays != otherTrialDays) {
            return true;
        }
        for (Cadence c : Cadence.values()) {
            Long o = otherPrices.get(c);
            if (o == null || o != price(c)) {
                return true;
            }
        }
        return false;
    }

    public static Map<Cadence, Long> prices(long monthly, long quarterly, long annual) {
        Map<Cadence, Long> m = new EnumMap<>(Cadence.class);
        m.put(Cadence.MONTHLY, monthly);
        m.put(Cadence.QUARTERLY, quarterly);
        m.put(Cadence.ANNUAL, annual);
        return m;
    }

    /** Stripe lookup key of the current version's price for a cadence, e.g. {@code seller_growth_quarterly}. */
    public static String lookupKey(String planCode, Cadence cadence) {
        return planCode + "_" + cadence.name().toLowerCase(Locale.ROOT);
    }
}
