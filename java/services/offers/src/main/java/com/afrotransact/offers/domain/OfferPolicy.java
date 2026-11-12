package com.afrotransact.offers.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The thresholds of matched offers and the tax release (S383, S384). Nothing here is hard-coded in the services: defaults
 * come from configuration, staff change them and the stored copy wins.
 *
 * @param conditionNoteMaxLength the longest note on a used or renewed item
 * @param packageMaxEdgeIn       the longest edge of a Fulfilled by AfroTransact box, in inches
 * @param packageMaxWeightLb     the heaviest Fulfilled by AfroTransact box, in pounds
 * @param taxSweepBatchSize      how many held or unverified offers one tax release run looks at
 * @param updatedBy              who saved it (null for the configured defaults)
 * @param updatedAt              when (null for the configured defaults)
 * @param version                optimistic-locking version of the stored copy (0 for the defaults)
 */
public record OfferPolicy(int conditionNoteMaxLength, double packageMaxEdgeIn, double packageMaxWeightLb,
                          int taxSweepBatchSize, String updatedBy, Instant updatedAt, long version) {

    /** Why the values cannot be saved, by field; empty when they can. */
    public Map<String, String> problems() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (conditionNoteMaxLength < 20 || conditionNoteMaxLength > 1000) {
            errors.put("conditionNoteMaxLength", "Between 20 and 1000");
        }
        if (packageMaxEdgeIn < 1 || packageMaxEdgeIn > 500) {
            errors.put("packageMaxEdgeIn", "Between 1 and 500 inches");
        }
        if (packageMaxWeightLb < 1 || packageMaxWeightLb > 2000) {
            errors.put("packageMaxWeightLb", "Between 1 and 2000 pounds");
        }
        if (taxSweepBatchSize < 1 || taxSweepBatchSize > 5000) {
            errors.put("taxSweepBatchSize", "Between 1 and 5000");
        }
        return errors;
    }

    /** The same settings stored by {@code by} at {@code at}; the repository sets the version. */
    public OfferPolicy savedBy(String by, Instant at) {
        return new OfferPolicy(conditionNoteMaxLength, packageMaxEdgeIn, packageMaxWeightLb, taxSweepBatchSize, by, at,
                version);
    }
}
