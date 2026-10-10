package com.afrotransact.retail.domain;

/** Received vs expected units of one PO line on a receipt; over by more than the tolerance blocks completion. */
public record LineVariance(int poLineNo, String sku, int expected, int received, int damaged, boolean overBlocked) {

    public int diff() {
        return received - expected;
    }
}
