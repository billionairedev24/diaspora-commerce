package com.afrotransact.identity.application;

import java.util.UUID;

import com.afrotransact.identity.domain.OrgKind;

/**
 * The membership an accepted invitation produced.
 *
 * @param roleKey       the member's role now (their existing role when they already were a member)
 * @param alreadyMember the user was a member before; their role was left unchanged
 */
public record AcceptedInvitation(UUID invitationId, String orgId, String orgName, OrgKind orgKind, String roleKey,
                                 UUID userId, boolean alreadyMember) {
}
