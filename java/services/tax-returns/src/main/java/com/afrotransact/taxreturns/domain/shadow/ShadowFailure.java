package com.afrotransact.taxreturns.domain.shadow;

/** Why a provider gave no quote for a line (the state shown next to the provider in the shadow report). */
public enum ShadowFailure {
    /** The provider's credentials are not set, so it was not asked. */
    NOT_CONFIGURED,
    /** The provider did not answer within the shadow timeout. */
    TIMEOUT,
    /** The provider was asked and refused or failed (HTTP error, bad response). */
    ERROR
}
