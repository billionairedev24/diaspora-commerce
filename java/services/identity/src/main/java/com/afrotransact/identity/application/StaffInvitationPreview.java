package com.afrotransact.identity.application;

import java.time.Instant;

import com.afrotransact.identity.domain.InvitationStatus;

/** What an invite link is for, shown before the invitee chooses a password. */
public record StaffInvitationPreview(String firstName, String lastName, String email, String roleName,
                                     InvitationStatus status, Instant expiresAt) {
}
