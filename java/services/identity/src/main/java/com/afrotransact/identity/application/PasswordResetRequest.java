package com.afrotransact.identity.application;

/**
 * "Send me a reset code".
 *
 * @param email     as typed (normalised by the service)
 * @param ip        the requester's IP address (per-IP limit), or null
 * @param userAgent for the audit trail, or null
 * @param clientId  the OAuth client the hosted page was opened for, or {@code api}
 * @param channel   {@link SignInAttempt#HOSTED} or {@link SignInAttempt#FIRST_PARTY}
 */
public record PasswordResetRequest(String email, String ip, String userAgent, String clientId, String channel) {
}
