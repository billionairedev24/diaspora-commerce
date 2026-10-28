package com.afrotransact.identity.application;

/**
 * A completed reset.
 *
 * @param email           the account's email (prefills the sign-in page)
 * @param sessionsRevoked refresh-token sessions (apps, devices) and browser sessions that were signed out
 * @param staff           a staff account: two-step verification is still required at the next sign-in
 */
public record PasswordResetCompleted(String email, int sessionsRevoked, boolean staff) {
}
