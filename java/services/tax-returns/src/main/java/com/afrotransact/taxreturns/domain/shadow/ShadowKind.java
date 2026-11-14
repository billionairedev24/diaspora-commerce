package com.afrotransact.taxreturns.domain.shadow;

/** How the provider's shadow quote of a line compares with the in-house quote. */
public enum ShadowKind {
    /** Same outcome and same tax. */
    MATCH,
    /** One engine taxes the line and the other does not. */
    OUTCOME,
    /** Both tax the line but at a different rate or amount. */
    RATE,
    /** Reserved: outcome and rate both differ in a way a person must split up. */
    BOTH,
    /** The provider could not answer for the line (not configured, timed out or failed: see {@link ShadowFailure}). */
    PROVIDER_ERROR,
    /** No code of the provider is mapped (or verified) for the line's outcome, so the provider was not asked. */
    NO_CODE,
    /** The in-house engine has no decision for the line (blocked), so there is nothing to compare. */
    NO_DECISION;

    public boolean differs() {
        return this != MATCH;
    }
}
