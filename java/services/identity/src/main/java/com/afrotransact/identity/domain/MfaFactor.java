package com.afrotransact.identity.domain;

/** Second factors a user can use at sign-in. */
public enum MfaFactor {
    TOTP,
    SMS,
    RECOVERY_CODE
}
