package com.afrotransact.inventory.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.inventory.domain.StockLevels;
import com.afrotransact.inventory.domain.StockPosition;

/**
 * Stock positions. Every write path locks positions with {@link #lockBySkus} first, so all transactions take row
 * locks in the same (sku, node_id) order and cannot deadlock.
 */
public interface PositionRepository {

    /** Creates an empty position if none exists (no-op otherwise). */
    void ensureExists(String sku, String nodeId, String ownerOrg, Instant now);

    /** {@code SELECT … FOR UPDATE} of every position of these SKUs, ordered by (sku, node_id). */
    List<StockPosition> lockBySkus(Collection<String> skus);

    /** Stores the levels if {@code position.version()} still matches and returns the copy with version + 1. */
    StockPosition update(StockPosition position, Instant now);

    Optional<StockPosition> find(String sku, String nodeId);

    /** Positions of these SKUs (all nodes), optionally limited to one owner organisation. */
    List<StockPosition> findBySkus(Collection<String> skus, String ownerOrg);

    Page<StockPosition> search(PositionQuery query);

    /** Stored levels of every position keyed by {@code sku|nodeId}. */
    Map<String, StockLevels> allLevels();

    /** Filter for listing positions; null fields are ignored. */
    record PositionQuery(String sku, String nodeId, String ownerOrg, boolean lowStockOnly, int page, int size) {
    }
}
