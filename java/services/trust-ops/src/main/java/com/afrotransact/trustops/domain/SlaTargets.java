package com.afrotransact.trustops.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Service-level targets for every trust-ops queue.
 *
 * @param moderation           a listing in the moderation queue gets a decision
 * @param moderationHighRisk   the same when AI triage rates the listing high risk
 * @param moderationEscalated  a senior reviewer decides an escalated listing
 * @param sellerResponse       the seller answers a claim (A-to-z response window)
 * @param claimDecision        staff decide a claim once it is under review
 * @param claimEscalated       staff decide an escalated claim
 * @param supportFirstResponse an agent picks up a support case
 */
public record SlaTargets(Duration moderation, Duration moderationHighRisk, Duration moderationEscalated,
                         Duration sellerResponse, Duration claimDecision, Duration claimEscalated,
                         Duration supportFirstResponse) {

    public SlaTargets {
        Objects.requireNonNull(moderation, "moderation");
        Objects.requireNonNull(moderationHighRisk, "moderationHighRisk");
        Objects.requireNonNull(moderationEscalated, "moderationEscalated");
        Objects.requireNonNull(sellerResponse, "sellerResponse");
        Objects.requireNonNull(claimDecision, "claimDecision");
        Objects.requireNonNull(claimEscalated, "claimEscalated");
        Objects.requireNonNull(supportFirstResponse, "supportFirstResponse");
    }

    /** Defaults: 24 h moderation (4 h high risk, 8 h escalated), 72 h seller window, 5 days to decide a claim. */
    public static SlaTargets defaults() {
        return new SlaTargets(Duration.ofHours(24), Duration.ofHours(4), Duration.ofHours(8), Duration.ofHours(72),
                Duration.ofDays(5), Duration.ofHours(48), Duration.ofHours(4));
    }
}
