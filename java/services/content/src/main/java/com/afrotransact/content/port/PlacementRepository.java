package com.afrotransact.content.port;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.domain.placement.SlotKey;

/** Placements. */
public interface PlacementRepository {

    /** Filtered list; null filters mean "any". Newest first. */
    List<Placement> find(PlacementStatus status, SlotKey slot, int limit);

    /** Every placement in one of the statuses. */
    List<Placement> findByStatus(Collection<PlacementStatus> statuses);

    Optional<Placement> findById(String id);

    void insert(Placement placement);

    /** Saves when the stored version equals {@code placement.version()}; returns false on a concurrent change. */
    boolean update(Placement placement);

    /** Hard delete; returns false when no row matched. */
    boolean deleteById(String id);
}
