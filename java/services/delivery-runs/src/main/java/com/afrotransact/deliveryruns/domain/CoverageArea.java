package com.afrotransact.deliveryruns.domain;

/**
 * A coverage switch: a region row covers every city in it; a city row overrides its region row.
 *
 * @param key     region or city
 * @param on      pre-order shown there
 * @param version optimistic lock of the row (bumped on every change)
 */
public record CoverageArea(AreaKey key, boolean on, long version) {

    public CoverageArea {
        if (key == null) {
            throw new RuleViolation("delivery-runs.invalid-area", "An area key is required");
        }
    }

    /** A row not stored yet. */
    public CoverageArea(AreaKey key, boolean on) {
        this(key, on, 0);
    }
}
