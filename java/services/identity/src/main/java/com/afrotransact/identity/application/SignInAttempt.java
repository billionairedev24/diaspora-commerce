package com.afrotransact.identity.application;

import java.util.UUID;

/**
 * One sign-in attempt as seen by the brute-force protection and the audit trail.
 *
 * @param userId  account, when known
 * @param email   normalised email the attempt was for (account throttle key), or null (passkey, ticket)
 * @param ip      end user's IP address (IP throttle key), or null
 * @param channel {@code first-party} or {@code hosted}
 * @param method  PASSWORD, MFA_TOTP, MFA_SMS, MFA_RECOVERY_CODE, PASSKEY, LOGIN_TICKET
 * @param reason  short machine reason (bad-credentials, wrong-code, …)
 */
public record SignInAttempt(UUID userId, String email, String ip, String userAgent, String clientId, String channel,
                            String method, String reason) {

    public static final String FIRST_PARTY = "first-party";
    public static final String HOSTED = "hosted";
}
