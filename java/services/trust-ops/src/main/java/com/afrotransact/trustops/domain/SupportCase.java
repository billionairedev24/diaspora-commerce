package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A customer conversation handed to human support (Lola {@code ai.handoff.requested}) with a first-response SLA. */
public record SupportCase(UUID id, String reference, String source, UUID handoffId, String sessionId,
                          String customerId, String reason, String summary, SupportCaseStatus status, String assignee,
                          Sla sla, Instant openedAt, Instant resolvedAt, String resolution, long version) {

    public static final String SOURCE_LOLA = "LOLA_HANDOFF";

    public SupportCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sla, "sla");
        Objects.requireNonNull(openedAt, "openedAt");
    }

    public static SupportCase fromHandoff(UUID id, String reference, UUID handoffId, String sessionId,
                                          String customerId, String reason, String summary, Instant requestedAt,
                                          SlaPolicy policy) {
        String why = reason == null || reason.isBlank() ? "human_requested" : reason;
        String text = summary == null ? "" : summary.length() > 4000 ? summary.substring(0, 4000) : summary;
        return new SupportCase(id, reference, SOURCE_LOLA, handoffId, sessionId,
                customerId == null || customerId.isBlank() ? "unknown" : customerId,
                why.length() > 120 ? why.substring(0, 120) : why, text, SupportCaseStatus.OPEN, null,
                policy.supportFirstResponse(requestedAt), requestedAt, null, null, 0);
    }

    public SupportCase assign(String agent) {
        requireNotResolved();
        return new SupportCase(id, reference, source, handoffId, sessionId, customerId, reason, summary,
                SupportCaseStatus.IN_PROGRESS, Texts.required(agent, "assignee", "Assignee", 120), sla, openedAt,
                null, null, version);
    }

    public SupportCase resolve(String by, String text, Instant now) {
        requireNotResolved();
        return new SupportCase(id, reference, source, handoffId, sessionId, customerId, reason, summary,
                SupportCaseStatus.RESOLVED, assignee == null ? by : assignee, sla, openedAt, now,
                Texts.required(text, "resolution", "Resolution", 2000), version);
    }

    private void requireNotResolved() {
        if (status == SupportCaseStatus.RESOLVED) {
            throw new IllegalStateException("Support case " + reference + " is already resolved");
        }
    }
}
