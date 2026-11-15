package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.domain.InvitationStatus;

/**
 * A staff invitation as the console lists it: names and emails, never the token.
 *
 * @param invitedByName who invited (a name; the subject when no account is found)
 */
public record StaffInvitationView(UUID id, String email, String firstName, String lastName, String roleKey,
                                  String roleName, InvitationStatus status, String reason, String invitedBy,
                                  String invitedByName, Instant createdAt, Instant expiresAt, Instant lastSentAt,
                                  int sendCount) {
}
