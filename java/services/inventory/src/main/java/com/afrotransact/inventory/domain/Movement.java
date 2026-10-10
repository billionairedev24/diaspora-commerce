package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the append-only stock ledger: what happened to one position (and lot), by whom and why,
 * with the position's levels before and after.
 *
 * @param id        ledger sequence (0 before it is stored)
 * @param qty       units moved (always positive; direction is in the deltas)
 * @param reference order id, receipt reference, transfer id or count id
 */
public record Movement(long id, Instant occurredAt, MovementType type, String sku, String nodeId, String ownerOrg,
                       UUID lotId, String lotNo, int qty, StockLevels before, StockLevels after, String reference,
                       String actor, String reason, String correlationId) {

    public StockLevels delta() {
        return after.minus(before);
    }
}
