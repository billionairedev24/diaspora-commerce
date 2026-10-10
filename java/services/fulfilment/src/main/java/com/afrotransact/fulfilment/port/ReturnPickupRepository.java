package com.afrotransact.fulfilment.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.ReturnPickup;
import com.afrotransact.fulfilment.domain.ReturnPickupStatus;

/** Return pickups (one per RMA). */
public interface ReturnPickupRepository {

    /** Inserts unless the RMA already has a pickup ({@code ON CONFLICT (rma_id) DO NOTHING}); true when inserted. */
    boolean insert(ReturnPickup pickup);

    Optional<ReturnPickup> find(String id);

    Optional<ReturnPickup> findByRma(String rmaId);

    Optional<ReturnPickup> findByStop(String stopId);

    /** {@code SELECT … FOR UPDATE}. */
    Optional<ReturnPickup> lock(String id);

    /** Pickups by status (all when null), optionally at one depot, earliest collect-by first. */
    List<ReturnPickup> list(ReturnPickupStatus status, String depotNodeId, int limit);

    void update(ReturnPickup pickup);
}
