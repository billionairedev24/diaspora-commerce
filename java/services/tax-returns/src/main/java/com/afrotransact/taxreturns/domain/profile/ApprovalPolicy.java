package com.afrotransact.taxreturns.domain.profile;

/**
 * Which classification records need an admin tax review before they are charged. ALL is the design default
 * (docs/engineering/category-tax-design.md section 6: READY means RESOLVED and APPROVED). SENSITIVE lets a record whose
 * profile is ACTIVE, that carries no evidence fact and whose outcome is TAXABLE be approved by the system; anything
 * exempt, evidence-based or gated always goes to a person.
 */
public enum ApprovalPolicy {
    ALL, SENSITIVE;

    /** Whether a person must review a record with this profile and resolution. */
    public boolean requiresReview(TaxProfile profile, Resolution resolution) {
        if (this == ALL) {
            return true;
        }
        boolean evidence = profile.facts().stream().anyMatch(f -> f.definition().evidence());
        return profile.status() != ProfileStatus.ACTIVE || evidence || resolution.status() != ResolveStatus.RESOLVED
                || resolution.outcome() != Outcome.TAXABLE;
    }
}
