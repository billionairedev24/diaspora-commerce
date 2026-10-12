package com.afrotransact.payments.domain;

/** What a negotiated rate replaces: the product commission or the service connection fee. */
public enum NegotiatedRateType {
    COMMISSION(FeeKind.PRODUCT),
    CONNECTION_FEE(FeeKind.SERVICE);

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
