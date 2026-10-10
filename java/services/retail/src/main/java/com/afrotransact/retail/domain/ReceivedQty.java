package com.afrotransact.retail.domain;

/** Units of one PO line counted on a goods receipt (good + damaged = received). */
public record ReceivedQty(int poLineNo, int good, int damaged) {

    public ReceivedQty {
        if (good < 0 || damaged < 0) {
            throw new IllegalArgumentException("Quantities cannot be negative");
        }
    }
}
