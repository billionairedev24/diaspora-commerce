package com.afrotransact.trustops.application;

import java.time.Instant;
import java.util.UUID;

/** ai-gateway's {@code ai.handoff.requested} payload (summary already redacted). */
public record HandoffRequest(UUID handoffId, String sessionId, String subject, String reason, String summary,
                             Instant requestedAt) {
}
