package com.afrotransact.payments.domain;

/** What a negotiated rate replaces: the product commission, the service connection fee or the FBA fee per unit. */
public enum NegotiatedRateType {
    COMMISSION(FeeKind.PRODUCT),
    CONNECTION_FEE(FeeKind.SERVICE),
    /** A per-unit FBA fee for every FBA line of the organisation (in minor units, not basis points). */
    FBA_FEE(FeeKind.PRODUCT);

    private final FeeKind kind;

    NegotiatedRateType(FeeKind kind) {
        this.kind = kind;
    }

    public FeeKind kind() {
        return kind;
    }

    public static NegotiatedRateType of(FeeKind kind) {
        return kind == FeeKind.SERVICE ? CONNECTION_FEE : COMMISSION;
    }
}
