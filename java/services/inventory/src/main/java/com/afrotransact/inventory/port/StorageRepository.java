package com.afrotransact.inventory.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.afrotransact.inventory.domain.SkuDimensions;

/** Unit dimensions per SKU and the daily storage snapshots that FBA storage is billed from (S206). */
public interface StorageRepository {

    void saveDimensions(SkuDimensions dimensions, String actorId, Instant at);

    Optional<StoredDimensions> findDimensions(String sku);

    /**
     * Replaces the snapshot of {@code day} with the stock now physically held in fulfilment centres (on hand +
     * damaged) per owner and node; units without dimensions count {@code defaultUnitCubicFeet} each.
     *
     * @return number of (owner, node) rows written
     */
    int snapshot(LocalDate day, BigDecimal defaultUnitCubicFeet, Instant at);

    /** Snapshot totals per owner and day in [from, to] (all nodes), optionally for one owner. */
    List<StorageDay> usage(LocalDate from, LocalDate to, String ownerOrg);

    /** Stored dimensions with their derived unit volume. */
    record StoredDimensions(SkuDimensions dimensions, BigDecimal unitCubicFeet, String updatedBy, Instant updatedAt) {
    }

    /**
     * Storage of one owner on one day.
     *
     * @param cubicFeet        cubic feet held that day, i.e. the day's cubic-feet-days
     * @param unmeasuredUnits  units billed at the default unit volume because their SKU has no dimensions
     */
    record StorageDay(String ownerOrg, LocalDate day, long units, long unmeasuredUnits, BigDecimal cubicFeet) {
    }
}
