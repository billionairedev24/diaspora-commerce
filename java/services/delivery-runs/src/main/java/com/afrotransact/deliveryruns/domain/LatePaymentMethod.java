package com.afrotransact.deliveryruns.domain;

/** How a late order is paid. */
public enum LatePaymentMethod {
    /** The customer's saved default card, charged off-session. */
    SAVED_CARD,
    /** A payment link sent by SMS; the order awaits payment until it is used. */
    PAYMENT_LINK
}
