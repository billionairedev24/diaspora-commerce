package com.afrotransact.taxreturns.domain.profile;

/** What the tax rules decided for an item: charge tax, no tax, or no decision possible (unitemised bundle). */
public enum Outcome {
    EXEMPT, TAXABLE, BLOCKED
}
