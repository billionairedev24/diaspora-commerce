package com.afrotransact.payments.domain;

/** What a credit ledger entry does to the balance. */
public enum CreditKind {
    /** Credit added: staff, promotion, referral, membership, gift card redeemed. */
    GRANT,
    /** Credit used to pay for an order (negative). */
    SPEND,
    /** Credit given back: an order's credit portion, or its card portion the shopper or staff chose as credit. */
    REFUND,
    /** A staff correction, positive or negative. */
    ADJUSTMENT
}
