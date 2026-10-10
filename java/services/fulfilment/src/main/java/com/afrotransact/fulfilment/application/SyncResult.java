package com.afrotransact.fulfilment.application;

import java.time.Instant;
import java.util.List;

/**
 * Result of a sync batch.
 *
 * @param deviceId         device
 * @param lastSequence     highest sequence consumed for the device; the app drops events up to it from its outbox
 * @param expectedSequence next sequence the server expects
 * @param signOut          driver access was revoked: sign out after this sync (no data is lost)
 * @param revokedAt        when access was revoked; events captured later are refused
 * @param results          one per event, in sequence order
 */
public record SyncResult(String deviceId, long lastSequence, long expectedSequence, boolean signOut,
                         Instant revokedAt, List<EventResult> results) {
}
