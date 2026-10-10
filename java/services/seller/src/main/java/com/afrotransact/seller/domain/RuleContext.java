package com.afrotransact.seller.domain;

import java.time.LocalDate;
import java.util.Set;

/** Facts the application rules need: org kind, the live markets and today's date. */
public record RuleContext(OrgKind kind, Set<String> liveCountries, LocalDate today) {

    public RuleContext {
        liveCountries = Set.copyOf(liveCountries);
    }
}
