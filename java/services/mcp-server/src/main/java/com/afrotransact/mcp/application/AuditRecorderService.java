package com.afrotransact.mcp.application;

import com.afrotransact.mcp.port.AuditTrail;

/**
 * Durable audit of tool calls: events go straight to the broker; when it refuses them they are spooled and replayed
 * in order once it is back.
 */
public interface AuditRecorderService {

    /** Records one event without ever failing the tool call. */
    void record(AuditTrail.ToolInvoked event);

    /**
     * Replays spooled events to the broker in spool order, stopping at the first refusal so order is kept.
     *
     * @return number of events replayed in this run
     */
    int replaySpool();
}
