package com.afrotransact.identity.application;

/**
 * The invitee's side: the token from the link and the password they choose. {@code phone} is optional (the invitee can
 * add and verify it later in their security settings). Neither the token nor the password is ever logged.
 */
public record AcceptStaffInvitationCommand(String token, String password, String confirmPassword, String phone,
                                           String phoneRegion) {

    @Override
    public String toString() {
        return "AcceptStaffInvitationCommand[redacted]";
    }
}
