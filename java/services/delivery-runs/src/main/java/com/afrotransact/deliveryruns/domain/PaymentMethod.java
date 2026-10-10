package com.afrotransact.deliveryruns.domain;

/** Payment methods pre-order checkouts may use. */
public enum PaymentMethod {
    CARD,
    /** Apple Pay, Google Pay, Link. */
    WALLET,
    ACH
}
