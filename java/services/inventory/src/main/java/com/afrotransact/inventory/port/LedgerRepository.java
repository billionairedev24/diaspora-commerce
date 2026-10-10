package com.afrotransact.inventory.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.inventory.domain.Movement;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.StockLevels;

/** The append-only stock ledger (the table rejects UPDATE, DELETE and TRUNCATE). */
public interface LedgerRepository {

    void append(List<Movement> movements);

    Page<Movement> search(MovementQuery query);

    /** Σ deltas per position key {@code sku|nodeId}. */
    Map<String, StockLevels> sumsByPosition();

    /** Units of {@code types} already recorded for an order reference and SKU. */
    int sumQty(String reference, String sku, List<MovementType> types);

    /** Filter for the movement history; null fields are ignored. */
    record MovementQuery(String sku, String nodeId, UUID lotId, String reference, String ownerOrg, MovementType type,
                         int page, int size) {
    }
}
