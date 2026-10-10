package com.afrotransact.payments.domain;

/** Who receives part of a charge. */
public enum RecipientKind {
    /** Marketplace seller or service provider (pays commission). */
    SELLER,
    /**
     * AfroTransact Retail, the first-party merchant of record: no commission, no FBA fee, only its Stripe-fee share;
     * its net is AfroTransact revenue (4400), never transferred or paid out.
     */
    RETAIL,
    /** AfroTransact the platform: commission, FBA fees, AfroTransact shipping, leftover Stripe-fee cents. */
    PLATFORM
}
