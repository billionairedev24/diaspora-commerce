package com.afrotransact.identity.application;

import java.util.UUID;

/**
 * Staff invitations replace creating an account with a temporary password. Staff with {@code access:admin} invite
 * a person by name, work email and platform role (with a reason); identity stores a pending invitation with a
 * single-use token that expires (72 hours by default) and is kept only as a hash; the notifications service emails the
 * link; the invitee opens it and sets their own password, which creates the account. Every step is in the access
 * change log. Listing needs {@code access:read} (or admin); inviting, resending and revoking need {@code access:admin}.
 */
public interface StaffInvitationService {

    StaffInvitationView invite(InviteStaffCommand command);

    /** Open invitations (pending, and expired ones that were never used), newest first. */
    PageView<StaffInvitationView> open(int page, int size);

    /** Issues a new token and a new expiry and emails it again; the old link stops working. */
    StaffInvitationView resend(UUID invitationId);

    void revoke(UUID invitationId, String reason);

    /** Public: what a link is for (name, email, role, status). 404 for a token that is not an invitation. */
    StaffInvitationPreview preview(String token);

    /** Public: the invitee sets a password; the account is created enabled, with the invited role, and the token is spent. */
    AcceptedStaffInvitation accept(AcceptStaffInvitationCommand command);
}
