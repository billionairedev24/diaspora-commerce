package com.afrotransact.identity.domain;

import java.time.Instant;

/**
 * A seller or provider organisation members can act for. Approval status is owned by the seller service.
 *
 * @param id     the seller service's organisation id ({@code org_…}); the token {@code org} claim
 * @param market ISO country of the business (for example {@code US}), null when unknown
 */
public record Organization(String id, OrgKind kind, String name, OrgStatus status, String market,
                           Instant updatedAt) {
}
