package com.afrotransact.catalog.domain;

/** Who ships the product: the seller, or AfroTransact from a fulfilment centre (stock sent in with an inbound plan). */
public enum FulfilmentMode {
    SELLER,
    AFROTRANSACT
}
