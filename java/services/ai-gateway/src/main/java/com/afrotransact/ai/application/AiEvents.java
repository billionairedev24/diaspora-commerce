package com.afrotransact.ai.application;

/** Event types and aggregates published through the transactional outbox (topic {@code at.ai-handoff.events}). */
public final class AiEvents {

    public static final String HANDOFF_REQUESTED = "ai.handoff.requested";
    public static final String AGGREGATE_HANDOFF = "ai-handoff";

    private AiEvents() {
    }
}
