package com.afrotransact.taxreturns.domain.tax;

/** Who works out the sales tax of an order. */
public enum TaxMethod {
    /** AfroTransact's own rules over the rate tables and outcome codes (state, local, exempt items). No external calls. */
    RULES,
    /** Stripe Tax, with our own rules as a visible fallback (provider RULES-FALLBACK) when Stripe cannot answer. */
    STRIPE_TAX,
    /** Avalara AvaTax (skeleton adapter: selectable only once it is configured). */
    AVALARA;

    /** The provider this method asks, or null for RULES. */
    public TaxProviderId provider() {
        return switch (this) {
            case RULES -> null;
            case STRIPE_TAX -> TaxProviderId.STRIPE;
            case AVALARA -> TaxProviderId.AVALARA;
        };
    }
}
