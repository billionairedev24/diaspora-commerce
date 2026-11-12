package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ExemptionStatus;
import com.afrotransact.catalog.domain.GtinExemptionRequest;

/** Store of {@link GtinExemptionRequest}s (optimistic locking on {@code version}). */
public interface GtinExemptionRepository {

    GtinExemptionRequest insert(GtinExemptionRequest request);

    /**
     * Stores a decision when {@code request.version()} is still the stored one, returns it with version + 1.
     *
     * @throws com.afrotransact.catalog.domain.StaleVersionException when somebody decided it meanwhile
     */
    GtinExemptionRequest update(GtinExemptionRequest request);

    Optional<GtinExemptionRequest> findById(UUID id);

    /** The waiting request of a page, if any. */
    Optional<GtinExemptionRequest> findOpenByProduct(UUID productId);

    List<GtinExemptionRequest> findByOrg(String requestedByOrg);

    /** Oldest first (the SLA clock); {@code status} null for every status. */
    Page search(ExemptionStatus status, int page, int size);

    long countOpen();

    Optional<Instant> oldestOpenSince();

    record Page(List<GtinExemptionRequest> items, long total, int page, int size) {

        public Page {
            items = List.copyOf(items);
        }
    }
}
