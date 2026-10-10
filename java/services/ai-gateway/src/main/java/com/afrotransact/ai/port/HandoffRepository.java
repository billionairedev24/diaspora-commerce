package com.afrotransact.ai.port;

import java.time.Instant;
import java.util.UUID;

/** Hand-offs from Lola to human support (the support console reads them via the ai.handoff.requested event). */
public interface HandoffRepository {

    void insert(Handoff handoff);

    /**
     * A hand-off request.
     *
     * @param id        hand-off id
     * @param sessionId Lola session
     * @param subject   shopper subject
     * @param reason    why (shopper asked, model decided, safety)
     * @param summary   redacted summary for the agent
     * @param at        when
     */
    record Handoff(UUID id, String sessionId, String subject, String reason, String summary, Instant at) {
    }
}
