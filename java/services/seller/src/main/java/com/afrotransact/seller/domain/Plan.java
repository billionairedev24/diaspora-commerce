package com.afrotransact.seller.domain;

import java.util.List;

/** A seller or provider plan; prices for every cadence derive from {@code monthlyCents}. */
public record Plan(String code, OrgKind kind, String name, String tagline, long monthlyCents, int trialDays,
                   List<String> features, int sortOrder) {

    public Plan {
        features = features == null ? List.of() : List.copyOf(features);
    }

    public boolean isFree() {
        return monthlyCents == 0;
    }
}
