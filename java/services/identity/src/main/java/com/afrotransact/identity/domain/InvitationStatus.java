package com.afrotransact.identity.domain;

/** Lifecycle of an organisation invitation. {@code EXPIRED} is derived from the expiry, never stored. */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED,
    EXPIRED
}
