package com.afrotransact.taxreturns.domain.profile;

/**
 * Why a classification record needs a person, derived from what is stored (its review state and its last resolution),
 * so the review queue can show it and filter by it. Read-only: it never changes a decision. The SQL of
 * {@code JdbcVariantClassificationRepository} filters by the same rules; keep the two in step.
 */
public enum ReviewReason {
    /** Approved by the system under the risk-based rule, live, waiting for a person to confirm it. */
    AUDIT_SAMPLE,
    /** The category waits on an open decision about which code applies. */
    CONFLICT,
    /** The category needs the tax professional's sign-off before anything in it can go live. */
    REGULATED_CATEGORY,
    /** The category uses a generic provider code that must be verified for this product. */
    CODE_TO_VERIFY,
    /** As described this cannot be sold: a mixed bundle must be itemised. */
    BLOCKED,
    /** No decision row of the category matches the answers. */
    NO_MATCHING_RULE,
    /** A required answer is still missing. */
    MISSING_FACT,
    /** The facts or the category changed after the last approval. */
    CHANGED,
    /** Sent back to the seller; it returns when they change it. */
    WITH_SELLER,
    /** None of the above: a new or changed listing the rules resolve, waiting for its first check. */
    NEW_LISTING;

    /** The reason this record needs a person; null for an approved record that is not in the audit sample. */
    public static ReviewReason of(VariantClassification v) {
        switch (v.status()) {
            case APPROVED:
                return v.auditItem() ? AUDIT_SAMPLE : null;
            case STALE:
                return CHANGED;
            case REJECTED:
                return WITH_SELLER;
            default:
                return ofResolution(v.resolution());
        }
    }

    private static ReviewReason ofResolution(Resolution r) {
        if (r == null) {
            return NO_MATCHING_RULE;
        }
        switch (r.status()) {
            case CONFLICT_OPEN:
                return CONFLICT;
            case NEEDS_APPROVAL:
                return REGULATED_CATEGORY;
            case REFINE_CODE:
                return CODE_TO_VERIFY;
            case BLOCKED:
                return BLOCKED;
            case MISSING_FACTS:
                return r.missingFacts().isEmpty() ? NO_MATCHING_RULE : MISSING_FACT;
            default:
                return NEW_LISTING;
        }
    }
}
