package com.afrotransact.trustops.domain;

import java.time.Instant;

/** Starts the right timer for each queue event. */
public interface SlaPolicy {

    Sla moderation(Instant enqueuedAt, ModerationPriority priority);

    Sla moderationEscalated(Instant escalatedAt);

    Sla sellerResponse(Instant openedAt);

    Sla claimDecision(Instant reviewStartedAt);

    Sla claimEscalated(Instant escalatedAt);

    Sla supportFirstResponse(Instant openedAt);
}
