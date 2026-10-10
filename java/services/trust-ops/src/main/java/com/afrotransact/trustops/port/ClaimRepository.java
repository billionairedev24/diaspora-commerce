package com.afrotransact.trustops.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.domain.Claim;
import com.afrotransact.trustops.domain.ClaimStatus;

/** Disputes / A-to-z claims and their timeline. */
public interface ClaimRepository {

    /** Next human reference, {@code AZ-10001}, {@code AZ-10002} … */
    String nextReference();

    /** Throws a conflict when the order / shipment already has an open claim. */
    Claim insert(Claim claim);

    Claim update(Claim claim);

    Optional<Claim> findById(UUID id);

    Page<Claim> search(ClaimQuery query, int page, int size);

    /** Claims in {@code status} whose SLA is breached at {@code now}. */
    List<Claim> findBreached(ClaimStatus status, Instant now, int limit);

    void appendHistory(UUID claimId, ClaimHistoryEntry entry);

    List<ClaimHistoryEntry> history(UUID claimId);
}
