package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.domain.InvitationStatus;
import com.afrotransact.identity.domain.OrgKind;

/**
 * An organisation invitation as shown to the team page or the invitee.
 *
 * @param status PENDING, ACCEPTED, REVOKED or EXPIRED (derived from {@code expiresAt})
 */
public record InvitationView(UUID id, String orgId, String orgName, OrgKind orgKind, String email, String roleKey,
                             String roleName, InvitationStatus status, String invitedBy, Instant createdAt,
                             Instant expiresAt) {
}
