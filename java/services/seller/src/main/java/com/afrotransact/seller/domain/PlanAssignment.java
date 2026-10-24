package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Staff moved an organisation to a plan (S306), with the reason. {@code applied} is what the change did:
 * IMMEDIATE_UPGRADE, AT_RENEWAL or NO_CHANGE.
 */
public record PlanAssignment(UUID id, String orgId, String fromPlan, String toPlan, Cadence cadence, String applied,
                             String reason, String assignedBy, Instant assignedAt) {
}
