package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.Objects;

/** {@link SlaPolicy} on calendar time with configurable {@link SlaTargets}. */
public final class DefaultSlaPolicy implements SlaPolicy {

    private final SlaTargets targets;

    public DefaultSlaPolicy(SlaTargets targets) {
        this.targets = Objects.requireNonNull(targets, "targets");
    }

    @Override
    public Sla moderation(Instant enqueuedAt, ModerationPriority priority) {
        return Sla.starting(enqueuedAt, priority == ModerationPriority.HIGH ? targets.moderationHighRisk()
                : targets.moderation());
    }

    @Override
    public Sla moderationEscalated(Instant escalatedAt) {
        return Sla.starting(escalatedAt, targets.moderationEscalated());
    }

    @Override
    public Sla sellerResponse(Instant openedAt) {
        return Sla.starting(openedAt, targets.sellerResponse());
    }

    @Override
    public Sla claimDecision(Instant reviewStartedAt) {
        return Sla.starting(reviewStartedAt, targets.claimDecision());
    }

    @Override
    public Sla claimEscalated(Instant escalatedAt) {
        return Sla.starting(escalatedAt, targets.claimEscalated());
    }

    @Override
    public Sla supportFirstResponse(Instant openedAt) {
        return Sla.starting(openedAt, targets.supportFirstResponse());
    }
}
