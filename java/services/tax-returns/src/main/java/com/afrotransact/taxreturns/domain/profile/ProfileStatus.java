package com.afrotransact.taxreturns.domain.profile;

/**
 * The gate of a category profile. Precedence when several apply: CONFLICT_OPEN, BLOCKED_BUNDLE, NEEDS_APPROVAL,
 * REFINE_CODE. ACTIVE means no gate.
 */
public enum ProfileStatus {
    ACTIVE, NEEDS_APPROVAL, BLOCKED_BUNDLE, REFINE_CODE, CONFLICT_OPEN;

    /** True when a tax professional's sign-off is needed to lift this gate. */
    public boolean needsSignOff() {
        return this == NEEDS_APPROVAL || this == CONFLICT_OPEN || this == BLOCKED_BUNDLE;
    }
}
