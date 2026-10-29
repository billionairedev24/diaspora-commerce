package com.afrotransact.seller.domain;

import java.util.List;
import java.util.Map;

/** Staff input for a plan's metadata (no prices: those are versioned, see {@link PlanPricesInput}). */
public record PlanInput(String code, OrgKind kind, String name, String tagline, List<String> features,
                        Map<String, String> limits, PlanVisibility visibility, Integer sortOrder) {

    public PlanInput {
        features = features == null ? List.of() : List.copyOf(features);
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }
}
