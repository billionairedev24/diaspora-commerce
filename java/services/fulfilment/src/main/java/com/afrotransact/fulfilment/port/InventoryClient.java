package com.afrotransact.fulfilment.port;

import java.util.List;
import java.util.Optional;

/** inventory-service reservations (read with the service token; inventory:reserve). */
public interface InventoryClient {

    /** {@code GET /v1/reservations/{orderId}}: per SKU the node and the FEFO lots allocated. */
    Optional<List<ReservedLine>> reservation(String orderId);

    /** One reserved line. */
    record ReservedLine(String sku, String nodeId, int qty, List<String> lotIds) {
    }
}
