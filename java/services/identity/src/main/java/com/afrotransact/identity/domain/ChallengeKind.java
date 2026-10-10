package com.afrotransact.identity.domain;

/** First-party sign-in challenges. */
public enum ChallengeKind {
    /** Password accepted, second factor pending. */
    MFA,
    /** WebAuthn assertion requested (discoverable credential, user not known yet). */
    PASSKEY
}
