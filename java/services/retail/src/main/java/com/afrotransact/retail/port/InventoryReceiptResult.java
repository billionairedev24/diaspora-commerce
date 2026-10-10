package com.afrotransact.retail.port;

import java.util.List;

/** Lots created by inventory for a receipt; {@code replayed} when the Idempotency-Key was seen before. */
public record InventoryReceiptResult(String receiptRef, List<InventoryLot> lots, boolean replayed) {

    public InventoryReceiptResult {
        lots = List.copyOf(lots);
    }
}
