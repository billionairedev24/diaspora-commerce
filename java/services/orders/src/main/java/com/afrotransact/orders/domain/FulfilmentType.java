package com.afrotransact.orders.domain;

/** Who fulfils a shipment. Decides which actors own which status steps (architecture §17c "Who can do what"). */
public enum FulfilmentType {
    /** The seller picks, packs and hands over to a carrier or an AfroTransact driver. */
    SELLER_FULFILLED,
    /** Seller stock held in an AfroTransact fulfilment centre (FBA). */
    FBA_AFROTRANSACT,
    /** AfroTransact Retail's own (1P) stock. */
    RETAIL_1P;

    /** AfroTransact staff and fulfilment ops own every step. */
    public boolean afrotransactFulfilled() {
        return this != SELLER_FULFILLED;
    }

    /** Stock is reserved and committed in the inventory service. */
    public boolean inventoryTracked() {
        return this != SELLER_FULFILLED;
    }
}
