package com.afrotransact.taxreturns.domain.profile;

/** Result of resolving a category and its facts. Only RESOLVED may be published or charged. */
public enum ResolveStatus {
    RESOLVED, MISSING_FACTS, BLOCKED, NEEDS_APPROVAL, REFINE_CODE, CONFLICT_OPEN;

    public boolean chargeable() {
        return this == RESOLVED;
    }
}
