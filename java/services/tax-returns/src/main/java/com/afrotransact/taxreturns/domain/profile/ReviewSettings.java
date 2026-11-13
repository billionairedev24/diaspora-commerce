package com.afrotransact.taxreturns.domain.profile;

import java.util.List;
import java.util.TreeSet;

/**
 * The staff-managed tax review settings.
 *
 * @param mode           RISK_BASED (the default: rules decide, a person reviews only exceptions) or ALL (every
 *                       classification is reviewed)
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
        mode = mode == null ? ReviewMode.RISK_BASED : mode;
        alwaysReview = alwaysReview == null ? DEFAULT_ALWAYS_REVIEW : List.copyOf(new TreeSet<>(alwaysReview));
        if (auditPercent < 0 || auditPercent > MAX_AUDIT_PERCENT) {
            throw new IllegalArgumentException("auditPercent must be between 0 and " + MAX_AUDIT_PERCENT);
        }
    }

    /** What applies when staff stored nothing: RISK_BASED, the default always-review list and 5% audit sampling. */
    public static ReviewSettings defaults() {
        return new ReviewSettings(ReviewMode.RISK_BASED, DEFAULT_ALWAYS_REVIEW, DEFAULT_AUDIT_PERCENT);
    }

    /**
     * What applies when a stored value exists but cannot be read: ALL. Staff may have chosen ALL on purpose, so when the
     * choice is unknown every classification goes to a person rather than none.
     */
    public static ReviewSettings safeFallback() {
        return new ReviewSettings(ReviewMode.ALL, DEFAULT_ALWAYS_REVIEW, DEFAULT_AUDIT_PERCENT);
    }

    public boolean riskBased() {
        return mode == ReviewMode.RISK_BASED;
    }
}
