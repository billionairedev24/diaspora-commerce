package com.afrotransact.identity.application;

import java.util.UUID;

/** The account the invitation created. */
public record AcceptedStaffInvitation(UUID userId, String email, String roleKey, String roleName) {
}
