package com.afrotransact.trustops.domain;

import java.util.Objects;

/**
 * Who did what to which entity.
 *
 * @param actorId    user id, OAuth client id or {@code system}
 * @param actorType  STAFF, SERVICE, SELLER, CUSTOMER, AI_AGENT, SYSTEM … when known
 * @param action     dotted action, for example {@code identity.role.granted} or {@code trustops.claim.decided}
 * @param entityType USER, ROLE, APPLICATION, ORG, PAYOUT, MCP_TOOL, SHIPMENT, LISTING, CLAIM …
 * @param entityId   id of the entity
 * @param orgId      organisation concerned, when any
 * @param summary    one human-readable line
 */
public record AuditFacts(String actorId, String actorType, String action, String entityType, String entityId,
                         String orgId, String summary) {

    public AuditFacts {
        actorId = actorId == null || actorId.isBlank() ? "system" : actorId;
        Objects.requireNonNull(action, "action");
        entityType = entityType == null || entityType.isBlank() ? "UNKNOWN" : entityType;
        entityId = entityId == null || entityId.isBlank() ? "-" : entityId;
    }
}
