package com.afrotransact.identity.application;

/**
 * The code from the email or text message plus the new password.
 *
 * @param channel {@link SignInAttempt#HOSTED} or {@link SignInAttempt#FIRST_PARTY}
 */
public record PasswordResetCompletion(String email, String code, String newPassword, String confirmPassword,
                                      String ip, String userAgent, String clientId, String channel) {

    /** Never print the code or the password. */
    @Override
    public String toString() {
        return "PasswordResetCompletion[email=" + email + ", channel=" + channel + "]";
    }
}
