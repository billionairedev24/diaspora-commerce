package com.afrotransact.retail.port;

/** inventory-service as seen by retail: lot receiving (S072), with QA holds on the receipt lines (S226). */
public interface InventoryClient {

    /**
     * Posts the lots of a completed receipt. Retries with the same {@code idempotencyKey} return the original lots.
     * Lines with a {@code holdReason} are received straight into quarantine.
     *
     * @throws InventoryRejectedException  when inventory rejects the lots (422/409)
     * @throws InventoryUnavailableException when inventory cannot be reached; retry later
     */
    InventoryReceiptResult receive(InventoryReceipt receipt, String idempotencyKey);
}
