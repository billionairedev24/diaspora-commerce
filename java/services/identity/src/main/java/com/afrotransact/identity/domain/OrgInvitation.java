package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation to join a seller or provider organisation with a role. Only the SHA-256 of the single-use token is
 * stored; the token itself travels once, in the {@code identity.invitation.created} event.
 *
 * @param invitedBy  subject of whoever created it (user id, or a client id)
 * @param acceptedBy account that accepted it
 * @param revokedBy  subject of whoever revoked it
 */
public record OrgInvitation(UUID id, String orgId, String email, String roleKey, String tokenHash,
                            InvitationStatus status, String invitedBy, Instant createdAt, Instant expiresAt,
                            Instant acceptedAt, UUID acceptedBy, Instant revokedAt, String revokedBy) {

    public OrgInvitation {
        email = UserAccount.normaliseEmail(email);
    }

    /** The stored status, or {@link InvitationStatus#EXPIRED} for a pending invitation past its expiry. */
    public InvitationStatus statusAt(Instant now) {
        return status == InvitationStatus.PENDING && !now.isBefore(expiresAt) ? InvitationStatus.EXPIRED : status;
    }

    public boolean pendingAt(Instant now) {
        return statusAt(now) == InvitationStatus.PENDING;
    }

    public OrgInvitation accepted(UUID userId, Instant now) {
        return new OrgInvitation(id, orgId, email, roleKey, tokenHash, InvitationStatus.ACCEPTED, invitedBy, createdAt,
                expiresAt, now, userId, revokedAt, revokedBy);
    }

    public OrgInvitation revoked(String actor, Instant now) {
        return new OrgInvitation(id, orgId, email, roleKey, tokenHash, InvitationStatus.REVOKED, invitedBy, createdAt,
                expiresAt, acceptedAt, acceptedBy, now, actor);
    }
}
