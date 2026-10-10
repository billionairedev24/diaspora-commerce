package com.afrotransact.taxreturns.domain.returns;

/** Who fulfilled the shipment (orders-service wire names). */
public enum FulfilmentType {
    SELLER_FULFILLED,
    FBA_AFROTRANSACT,
    RETAIL_1P;

    /** True when AfroTransact holds the stock: returns go back to an FC and through inventory. */
    public boolean afroTransactFulfilled() {
        return this != SELLER_FULFILLED;
    }
}
