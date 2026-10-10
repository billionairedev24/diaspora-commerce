package com.afrotransact.retail.port;

import java.util.UUID;

/** inventory-service as seen by retail: lot receiving (S072) and quarantine of QA-failed batches. */
public interface InventoryClient {

    /**
     * Posts the lots of a completed receipt. Retries with the same {@code idempotencyKey} return the original lots.
     *
     * @throws InventoryRejectedException  when inventory rejects the lots (422/409)
     * @throws InventoryUnavailableException when inventory cannot be reached; retry later
     */
    InventoryReceiptResult receive(InventoryReceipt receipt, String idempotencyKey);

    /** Quarantines a lot (QA failed at the dock). Quarantining an already quarantined lot is a no-op. */
    void quarantine(UUID lotId, String reason);
}
