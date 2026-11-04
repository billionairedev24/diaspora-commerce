package com.afrotransact.seller.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** Facts the application rules need: org kind, the live markets, today's date and the catalog's departments by code. */
public record RuleContext(OrgKind kind, Set<String> liveCountries, LocalDate today, Map<String, Department> departments) {

    public RuleContext {
        liveCountries = Set.copyOf(liveCountries);
        departments = departments == null ? Map.of() : Map.copyOf(departments);
    }

    /** Without the catalog's departments (steps that do not look at them). */
    public RuleContext(OrgKind kind, Set<String> liveCountries, LocalDate today) {
        this(kind, liveCountries, today, Map.of());
    }
}
