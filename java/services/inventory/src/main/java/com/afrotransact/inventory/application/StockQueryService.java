package com.afrotransact.inventory.application;

import java.util.List;

import com.afrotransact.inventory.domain.Movement;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.PositionRepository;

/** Read side: storefront availability, positions and the movement history. */
public interface StockQueryService {

    /** Public storefront availability for up to 100 SKUs (unknown SKUs report 0). */
    List<Availability> availability(List<String> skus);

    /** Positions with on hand, reserved, inbound and damaged (inventory:read; sellers see their own FBA stock). */
    Page<StockPosition> positions(PositionRepository.PositionQuery query);

    /** Ledger rows, newest first (inventory:read; sellers see their own). */
    Page<Movement> movements(LedgerRepository.MovementQuery query);
}
