package com.afrotransact.trustops.port;

import java.time.Instant;

import com.afrotransact.trustops.domain.ClaimStatus;

/** One line of a claim's timeline: who did what and the status change it caused. */
public record ClaimHistoryEntry(Instant at, String actorId, String action, ClaimStatus fromStatus,
                                ClaimStatus toStatus, String note) {
}
