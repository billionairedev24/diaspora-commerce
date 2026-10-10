package com.afrotransact.seller.domain;

/** Automatic enforcement decided by a compliance change. */
public enum Enforcement {
    NONE,
    PAUSE_PAYOUTS,
    RESUME_PAYOUTS,
    HIDE_LISTINGS,
    RESTORE_LISTINGS
}
