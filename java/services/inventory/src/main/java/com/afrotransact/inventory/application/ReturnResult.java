package com.afrotransact.inventory.application;

import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.ReturnCondition;

/** Where returned units went: back into their sellable lot, or into the lot's {@code -DMG} lot. */
public record ReturnResult(String orderId, String sku, int qty, ReturnCondition condition, Lot lot,
                           boolean replayed) {

    ReturnResult asReplay() {
        return new ReturnResult(orderId, sku, qty, condition, lot, true);
    }
}
