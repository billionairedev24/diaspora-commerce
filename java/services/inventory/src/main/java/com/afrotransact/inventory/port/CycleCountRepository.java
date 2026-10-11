package com.afrotransact.inventory.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.CycleCount;
import com.afrotransact.inventory.domain.CycleCountStatus;

/** Cycle counts and their lines (S281). */
public interface CycleCountRepository {

    /** Next human number, e.g. CC-000042. */
    String nextNumber();

    void insert(CycleCount count);

    /** Stores the count and its lines if the version still matches; returns the stored count. */
    CycleCount update(CycleCount count);

    Optional<CycleCount> findById(UUID id);

    Optional<CycleCount> lockById(UUID id);

    Page<CycleCount> search(CountQuery query);

    /** Filter for listing counts; null fields are ignored. */
    record CountQuery(String nodeId, CycleCountStatus status, String sku, String ownerOrg, int page, int size) {
    }
}
