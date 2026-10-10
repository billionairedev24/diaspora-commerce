package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingEnforcementRun;

/** Progress of paged hide / restore runs (S216). */
public interface ListingEnforcementRunRepository {

    void insert(ListingEnforcementRun run);

    Optional<ListingEnforcementRun> findById(UUID eventId);

    /** Reads and row-locks the run for the rest of the transaction (one page at a time per run). */
    Optional<ListingEnforcementRun> lock(UUID eventId);

    /** Saves the run if its version still matches (version + 1). */
    void update(ListingEnforcementRun run);

    /** Marks every running run of the organisation superseded. @return how many */
    int supersedeRunning(String orgId, Instant now);

    /** Running runs whose last page is older than {@code before} (crashed pods), oldest first. */
    List<UUID> stalled(Instant before, int limit);
}
