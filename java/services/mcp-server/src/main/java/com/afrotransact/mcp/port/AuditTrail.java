package com.afrotransact.mcp.port;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** Broker side of the audit of every MCP tool call ({@code mcp.tool.invoked} on {@code at.mcp-tool.events}). */
public interface AuditTrail {

    /**
     * Publishes one event. The returned stage completes when the broker acknowledged it and completes exceptionally
     * when it did not; the call itself may also throw when the broker cannot even be reached.
     */
    CompletionStage<Void> publish(ToolInvoked event);

    /**
     * One tool call. Arguments are never stored, only their hash.
     *
     * @param eventId       unique id (consumers de-duplicate on it)
     * @param tool          tool name
     * @param toolset       SHOPPER, SELLER or STAFF (empty for unknown tools)
     * @param subject       caller subject
     * @param clientId      OAuth client of the agent
     * @param orgId         organisation the caller acted for, may be null
     * @param argumentsHash {@code sha256:<hex>} of the canonical arguments
     * @param outcome       ok, error, denied, rate_limited or unknown_tool
     * @param durationMs    time spent in the tool
     * @param at            when
     */
    record ToolInvoked(String eventId, String tool, String toolset, String subject, String clientId, String orgId,
                       String argumentsHash, String outcome, long durationMs, Instant at) {
    }
}
