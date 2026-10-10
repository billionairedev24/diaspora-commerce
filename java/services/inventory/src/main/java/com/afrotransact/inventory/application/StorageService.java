package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.port.StorageRepository.StorageDay;
import com.afrotransact.inventory.port.StorageRepository.StoredDimensions;

/**
 * FBA storage volume for metered billing (S206): unit dimensions per SKU, an hourly-retaken snapshot of today's
 * stock in fulfilment centres, and cubic-feet-days per owner organisation and day for seller-service.
 */
public interface StorageService {

    /** Maximum number of days one usage request may cover. */
    int MAX_DAYS = 93;

    /** Sets a SKU's unit dimensions (staff with {@code inventory:adjust} or {@code inventory:receive}). */
    StoredDimensions setDimensions(String sku, BigDecimal lengthIn, BigDecimal widthIn, BigDecimal heightIn);

    /** A SKU's unit dimensions ({@code inventory:read}). */
    StoredDimensions dimensions(String sku);

    /** Retakes today's (UTC) snapshot; idempotent (the job, or staff with {@code inventory:adjust}). */
    SnapshotResult snapshotToday();

    /** Same as {@link #snapshotToday()} without an actor check (scheduler). */
    SnapshotResult snapshotTodayAsJob();

    /**
     * Storage per owner and day in [from, to]: SERVICE {@code inventory:storage.read} and staff {@code inventory:read}
     * see every owner (or {@code ownerOrg}); seller members only their own organisation.
     */
    List<StorageDay> usage(LocalDate from, LocalDate to, String ownerOrg);

    /** What a snapshot wrote. */
    record SnapshotResult(LocalDate day, int rows) {
    }
}
