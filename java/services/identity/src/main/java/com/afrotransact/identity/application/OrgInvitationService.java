package com.afrotransact.identity.application;

import java.util.List;
import java.util.UUID;

/**
 * Invitations to join a seller or provider organisation (S231). Members with {@code team:manage} (and staff/services
 * with {@code orgs:manage}) invite by email with an organisation role; notifications-service emails the single-use
 * link ({@code identity.invitation.created}, 7 days). The invitee accepts signed in with the same email, or signs up
 * from the link ({@code POST /v1/accounts} with {@code invitationToken}).
 */
public interface OrgInvitationService {

    /** Creates an invitation; an open invitation for the same email is replaced (works as "resend"). */
    InvitationView invite(String orgId, String email, String roleKey);

    /** Pending, unexpired invitations (same readers as the member directory). */
    List<InvitationView> pending(String orgId);

    void revoke(String orgId, UUID invitationId);

    /** The signed-in user accepts; their account email must match the invitation. */
    AcceptedInvitation accept(String token);

    /** Public: what an invitation link is for (organisation, role, email, status), for the accept and sign-up pages. */
    InvitationView preview(String token);
}
