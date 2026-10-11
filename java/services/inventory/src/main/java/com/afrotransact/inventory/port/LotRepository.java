package com.afrotransact.inventory.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MarkdownFlag;
import com.afrotransact.inventory.domain.StockLevels;

/** Lots. Callers lock the owning position first; lot locks are then uncontended. */
public interface LotRepository {

    void insert(Lot lot);

    /** Stores the lot if its version still matches. */
    void update(Lot lot);

    Optional<Lot> findById(UUID id);

    Optional<Lot> lockById(UUID id);

    List<Lot> lockByIds(Collection<UUID> ids);

    /** Sellable lots of one SKU at one node with unreserved units, locked, in FEFO order. */
    List<Lot> lockAllocatable(String sku, String nodeId);

    Optional<Lot> lockByKey(String sku, String lotNo, String nodeId);

    Page<Lot> search(LotQuery query);

    /**
     * Lots physically at a node (on hand &gt; 0 and not written off) for a cycle count, by bin, SKU and expiry.
     * Null filters are ignored; {@code binPrefix} matches the start of the bin.
     */
    List<Lot> findForCount(String nodeId, String sku, String binPrefix, String ownerOrg, int limit);

    /** Lots flagged for markdown (≤ 60 or ≤ 30 days), soonest expiry first. */
    List<Lot> findMarkdown(String ownerOrg, MarkdownFlag minimum);

    /** Ids of sellable or held lots at a node whose expiry is on or before {@code today}. */
    List<UUID> findExpiredIds(String nodeId, LocalDate today);

    /** Recomputes markdown flags of sellable lots at a node; returns how many lots changed flag. */
    int refreshMarkdownFlags(String nodeId, LocalDate today);

    /** Σ on hand / reserved / held quantity per position key {@code sku|nodeId} (inbound is zero). */
    Map<String, StockLevels> levelsByPosition();

    /** Filter for listing lots; null fields are ignored. */
    record LotQuery(String sku, String nodeId, String ownerOrg, LotStatus status, String lotNo, int page, int size) {
    }
}
