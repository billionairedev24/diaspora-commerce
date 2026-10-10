package com.afrotransact.deliveryruns.domain;

/**
 * A coverage switch: a region row covers every city in it; a city row overrides its region row.
 *
 * @param key region or city
 * @param on  pre-order shown there
 */
public record CoverageArea(AreaKey key, boolean on) {

    public CoverageArea {
        if (key == null) {
            throw new RuleViolation("delivery-runs.invalid-area", "An area key is required");
        }
    }
}
