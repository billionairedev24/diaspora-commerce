package com.afrotransact.mcp.application;

import java.time.Duration;

/**
 * Tuning of the durable audit path.
 *
 * @param replayBatch    spooled events read per round trip during replay
 * @param publishTimeout how long replay waits for the broker to acknowledge one event
 * @param replayLease    how long one replica holds the replay lease
 */
public record AuditSettings(int replayBatch, Duration publishTimeout, Duration replayLease) {

    public AuditSettings {
        if (replayBatch <= 0) {
            throw new IllegalArgumentException("replayBatch must be positive");
        }
    }
}
