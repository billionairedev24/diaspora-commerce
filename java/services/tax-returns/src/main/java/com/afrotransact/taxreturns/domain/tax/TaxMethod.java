package com.afrotransact.taxreturns.domain.tax;

/** Who works out the sales tax of an order. */
public enum TaxMethod {
    /** AfroTransact's own rules over the rate tables and tax codes (state, local, exempt items). No external calls. */
    RULES,
    /** Stripe Tax, with our own rules as the fallback when Stripe is unreachable or an item has no Stripe tax code. */
    STRIPE_TAX
}
