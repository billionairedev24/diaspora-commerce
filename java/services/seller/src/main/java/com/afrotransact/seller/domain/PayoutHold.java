package com.afrotransact.seller.domain;

/** Why payouts are paused: automatically for past-due requirements, by staff, or because the account was revoked. */
public enum PayoutHold {
    AUTO_PAST_DUE,
    MANUAL,
    DEAUTHORIZED
}
