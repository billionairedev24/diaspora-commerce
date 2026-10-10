package com.afrotransact.payments.domain;

/** Who receives part of a charge. */
public enum RecipientKind {
    /** Marketplace seller or service provider (pays commission). */
    SELLER,
    /** AfroTransact Retail, the first-party seller of record (no commission). */
    RETAIL,
    /** AfroTransact the platform: commission, FBA fees, AfroTransact shipping, leftover Stripe-fee cents. */
    PLATFORM
}
