package com.afrotransact.trustops.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.domain.SupportCase;
import com.afrotransact.trustops.domain.SupportCaseStatus;

/** Support cases opened from Lola hand-offs. */
public interface SupportCaseRepository {

    /** Next human reference, {@code SC-1001} … */
    String nextReference();

    SupportCase insert(SupportCase supportCase);

    SupportCase update(SupportCase supportCase);

    Optional<SupportCase> findById(UUID id);

    boolean existsByHandoff(UUID handoffId);

    /** Soonest due first; null status = not resolved. */
    Page<SupportCase> search(SupportCaseStatus status, int page, int size);
}
