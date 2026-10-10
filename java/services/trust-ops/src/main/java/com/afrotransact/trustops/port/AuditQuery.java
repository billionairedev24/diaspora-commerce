package com.afrotransact.trustops.port;

import java.time.Instant;

/** Audit log filters (all optional): actor, entity, action, organisation and an occurred-at range [from, to). */
public record AuditQuery(String actorId, String entityType, String entityId, String action, String orgId,
                         Instant from, Instant to) {
}
