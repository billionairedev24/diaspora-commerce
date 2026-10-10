package com.afrotransact.offers.domain;

/** REDEEMED counts against the limits; RELEASED (order cancelled or failed) gives the use back. */
public enum RedemptionStatus {
    REDEEMED,
    RELEASED
}
