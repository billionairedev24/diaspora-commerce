package com.afrotransact.identity.application;

import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.domain.UserSession;

/** Login & security page: password, sessions, linked social accounts. */
public interface SecurityService {

    /** Requires the current password when the account has one; new password follows the password policy. */
    void changePassword(String currentPassword, String newPassword, String confirmPassword);

    /** @param currentAccessToken the caller's bearer token, used to flag "this device" */
    List<UserSession> sessions(Optional<String> currentAccessToken);

    void revokeSession(String sessionId);

    /** "Sign out other devices"; returns how many sessions ended. */
    int revokeOtherSessions(Optional<String> currentAccessToken);

    LinkedAccountsView linkedAccounts();

    /** Cannot unlink the last sign-in method. */
    LinkedAccountsView unlink(String provider);
}
