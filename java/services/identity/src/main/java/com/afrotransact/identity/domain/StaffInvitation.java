package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation to become a member of staff with one platform role. Only the SHA-256 of the single-use token is
 * stored; the token itself travels once, in the {@code identity.staff-invitation.created} event.
 *
 * @param reason       why the person is being invited (kept in the access change log)
 * @param invitedBy    subject of whoever invited
 * @param lastSentAt   when the email was last issued (a resend issues a new token)
 * @param sendCount    how many times the email was issued
 * @param acceptedUser the account the invitee created
 */
public record StaffInvitation(UUID id, String email, String firstName, String lastName, String roleKey, String reason,
                              String tokenHash, InvitationStatus status, String invitedBy, Instant createdAt,
                              Instant expiresAt, Instant lastSentAt, int sendCount, Instant acceptedAt,
                              UUID acceptedUser, Instant revokedAt, String revokedBy) {

    public StaffInvitation {
        email = UserAccount.normaliseEmail(email);
    }

    /** The stored status, or {@link InvitationStatus#EXPIRED} for a pending invitation past its expiry. */
    public InvitationStatus statusAt(Instant now) {
        return status == InvitationStatus.PENDING && !now.isBefore(expiresAt) ? InvitationStatus.EXPIRED : status;
    }

    public boolean pendingAt(Instant now) {
        return statusAt(now) == InvitationStatus.PENDING;
    }

    public String displayName() {
        return (firstName + " " + lastName).trim();
    }

    /** A new token and expiry (resend): the old link stops working. */
    public StaffInvitation reissued(String newTokenHash, Instant now, Instant newExpiry) {
        return new StaffInvitation(id, email, firstName, lastName, roleKey, reason, newTokenHash, status, invitedBy,
                createdAt, newExpiry, now, sendCount + 1, acceptedAt, acceptedUser, revokedAt, revokedBy);
    }

    public StaffInvitation revoked(String actor, Instant now) {
        return new StaffInvitation(id, email, firstName, lastName, roleKey, reason, tokenHash,
                InvitationStatus.REVOKED, invitedBy, createdAt, expiresAt, lastSentAt, sendCount, acceptedAt,
                acceptedUser, now, actor);
    }

    public StaffInvitation accepted(UUID userId, Instant now) {
        return new StaffInvitation(id, email, firstName, lastName, roleKey, reason, tokenHash,
                InvitationStatus.ACCEPTED, invitedBy, createdAt, expiresAt, lastSentAt, sendCount, now, userId,
                revokedAt, revokedBy);
    }
}
