package com.afrotransact.catalog.domain.tax;

/**
 * The tax publish gate of a variant. Only {@link #READY} may go live. Mirrors {@code VariantTaxStatus} of the catalog
 * contract; the gate statuses come from the category's tax profile (docs/engineering/category-tax-design.md section 6).
 */
public enum TaxStatus {
    /** The rules resolved to a definite decision and the tax review (if any) approved it. */
    READY,
    /** A required fact is empty. The seller fills it. */
    MISSING_FACTS,
    /** Resolved; waiting for the tax review of the variant. */
    PENDING_REVIEW,
    /** The matched row, or the category, is blocked (unitemised bundle). */
    BLOCKED,
    /** The category's rule needs the tax professional's approval first. */
    NEEDS_APPROVAL,
    /** The category needs a refined provider code per variant first. */
    REFINE_CODE,
    /** The rule of the category is an open decision. */
    CONFLICT_OPEN,
    /** Tax review rejected the classification. */
    REJECTED;

    /** Parses a status or resolve status; RESOLVED reads as {@link #PENDING_REVIEW}, unknown values as null. */
    public static TaxStatus parse(String value) {
        if (value == null) {
            return null;
        }
        if ("RESOLVED".equals(value)) {
            return PENDING_REVIEW;
        }
        for (TaxStatus s : values()) {
            if (s.name().equals(value)) {
                return s;
            }
        }
        return null;
    }
}
