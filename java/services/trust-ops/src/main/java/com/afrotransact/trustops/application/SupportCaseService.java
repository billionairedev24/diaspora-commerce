package com.afrotransact.trustops.application;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.domain.SupportCaseStatus;
import com.afrotransact.trustops.port.Page;

/** Support-case queue fed by Lola hand-offs (support:respond). */
public interface SupportCaseService {

    /** Opens a case for a hand-off (inbox- and hand-off-id-deduplicated). */
    Optional<SupportCaseView> openFromHandoff(UUID eventId, HandoffRequest handoff);

    /** Soonest due first; {@code status} null = not resolved. */
    Page<SupportCaseView> queue(SupportCaseStatus status, int page, int size);

    SupportCaseView get(UUID id);

    /** Assigns to {@code assignee}, or to the caller when null. */
    SupportCaseView assign(UUID id, String assignee);

    SupportCaseView resolve(UUID id, String resolution);
}
