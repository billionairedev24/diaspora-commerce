package com.afrotransact.identity.domain;

/** Result of checking a submitted code. */
public enum VerificationOutcome {
    VALID,
    WRONG_CODE,
    EXPIRED,
    TOO_MANY_ATTEMPTS,
    ALREADY_USED
}
