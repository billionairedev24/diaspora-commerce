package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** A seller or provider organisation members can act for. Approval status is owned by the seller service. */
public record Organization(UUID id, OrgKind kind, String name, OrgStatus status, Instant updatedAt) {
}
