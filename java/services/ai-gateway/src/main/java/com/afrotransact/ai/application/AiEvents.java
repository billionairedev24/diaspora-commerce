package com.afrotransact.ai.application;

/** Event types and aggregates published through the transactional outbox (topics {@code at.ai-handoff.events}, {@code at.ai-spend-cap.events}). */
public final class AiEvents {

    public static final String HANDOFF_REQUESTED = "ai.handoff.requested";
    public static final String AGGREGATE_HANDOFF = "ai-handoff";

    /** The daily spend cap was changed (topic {@code at.ai-spend-cap.events}); copied into the audit log by trust-ops. */
    public static final String SPEND_CAP_CHANGED = "ai.spend-cap.changed";
    public static final String AGGREGATE_SPEND_CAP = "ai-spend-cap";

    private AiEvents() {
    }
}
