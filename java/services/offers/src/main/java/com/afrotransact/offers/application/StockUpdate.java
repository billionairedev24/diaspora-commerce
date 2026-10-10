package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

/**
 * Result of a seller stock adjustment.
 *
 * @param sku      seller SKU
 * @param quantity the quantity now declared on every open offer with that SKU
 * @param offerIds the offers that carry the SKU
 */
public record StockUpdate(String sku, int quantity, String reason, List<UUID> offerIds) {

    public StockUpdate {
        offerIds = List.copyOf(offerIds);
    }
}
