package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * A seller or provider plan with the prices and default trial of its current version. Prices per cadence are set by
 * staff ({@link PlanVersion}); {@link #standard} derives the historical rule (quarterly = 3 months less 5 %, annual =
 * 10 months) used by the seeded catalogue and tests.
 *
 * @param version the current (published) version these prices belong to; 0 for a plan never published
 */
public record Plan(String code, OrgKind kind, String name, String tagline, int version, long monthlyCents,
                   long quarterlyCents, long annualCents, int trialDays, List<String> features, int sortOrder,
                   PlanVisibility visibility, PlanStatus status, Map<String, String> limits) {

    private static final BigDecimal QUARTERLY_FACTOR = new BigDecimal("0.95");

    public Plan {
        features = features == null ? List.of() : List.copyOf(features);
        limits = limits == null ? Map.of() : Map.copyOf(limits);
        visibility = visibility == null ? PlanVisibility.PUBLIC : visibility;
        status = status == null ? PlanStatus.ACTIVE : status;
    }

    /** A published public plan priced by the standard cadence rule. */
    public static Plan standard(String code, OrgKind kind, String name, String tagline, long monthlyCents,
                                int trialDays, List<String> features, int sortOrder) {
        return new Plan(code, kind, name, tagline, 1, monthlyCents, standardPrice(monthlyCents, Cadence.QUARTERLY),
                standardPrice(monthlyCents, Cadence.ANNUAL), trialDays, features, sortOrder, PlanVisibility.PUBLIC,
                PlanStatus.ACTIVE, Map.of());
    }

    /** Quarterly = 3 months less 5 % (half-up to the cent), annual = 10 months. */
    public static long standardPrice(long monthlyCents, Cadence cadence) {
        return switch (cadence) {
            case MONTHLY -> monthlyCents;
            case QUARTERLY -> BigDecimal.valueOf(monthlyCents).multiply(BigDecimal.valueOf(3))
                    .multiply(QUARTERLY_FACTOR).setScale(0, RoundingMode.HALF_UP).longValueExact();
            case ANNUAL -> monthlyCents * 10;
        };
    }

    public long priceCents(Cadence cadence) {
        return switch (cadence) {
            case MONTHLY -> monthlyCents;
            case QUARTERLY -> quarterlyCents;
            case ANNUAL -> annualCents;
        };
    }

    public boolean isFree() {
        return monthlyCents == 0 && quarterlyCents == 0 && annualCents == 0;
    }

    /** New organisations may choose it (published and not retired). */
    public boolean isOpen() {
        return status == PlanStatus.ACTIVE && version > 0;
    }
}
