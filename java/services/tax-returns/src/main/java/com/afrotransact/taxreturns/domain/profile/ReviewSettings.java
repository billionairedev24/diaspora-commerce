package com.afrotransact.taxreturns.domain.profile;

import java.util.List;
import java.util.TreeSet;

/**
 * The staff-managed tax review settings.
 *
 * @param mode           ALL (every classification is reviewed) or RISK_BASED
 * @param alwaysReview   category codes that always go to review in RISK_BASED mode (sorted, no duplicates)
 * @param auditPercent   share of auto-approved items that is also queued as an audit item, 0 to 100
 */
public record ReviewSettings(ReviewMode mode, List<String> alwaysReview, int auditPercent) {

    /** Categories whose items always go to a person: regulated or high-risk goods (decision D7). */
    public static final List<String> DEFAULT_ALWAYS_REVIEW = List.of("baby-food-formula", "dietary-supplements",
            "gift-cards", "medical-devices", "medicated-skincare", "otc-medicines");

    public static final int DEFAULT_AUDIT_PERCENT = 5;
    public static final int MAX_AUDIT_PERCENT = 100;

    public ReviewSettings {
        mode = mode == null ? ReviewMode.ALL : mode;
        alwaysReview = alwaysReview == null ? DEFAULT_ALWAYS_REVIEW : List.copyOf(new TreeSet<>(alwaysReview));
        if (auditPercent < 0 || auditPercent > MAX_AUDIT_PERCENT) {
            throw new IllegalArgumentException("auditPercent must be between 0 and " + MAX_AUDIT_PERCENT);
        }
    }

    /** ALL, the default always-review list and 5% audit sampling. */
    public static ReviewSettings defaults() {
        return new ReviewSettings(ReviewMode.ALL, DEFAULT_ALWAYS_REVIEW, DEFAULT_AUDIT_PERCENT);
    }

    public boolean riskBased() {
        return mode == ReviewMode.RISK_BASED;
    }
}
