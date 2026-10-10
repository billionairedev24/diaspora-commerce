package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the append-only sign-in audit trail.
 *
 * @param method  PASSWORD, MFA_TOTP, MFA_SMS, MFA_RECOVERY_CODE, PASSKEY, LOGIN_TICKET
 * @param outcome SUCCEEDED, FAILED, MFA_REQUIRED, LOCKED, BLOCKED, TICKET_ISSUED, TICKET_REDEEMED, TICKET_REJECTED
 * @param channel {@code first-party} (BFF API) or {@code hosted} (authorization server pages)
 */
public record SignInAuditEntry(Instant at, UUID userId, String email, String clientId, String channel, String method,
                               String outcome, String reason, String ip, String userAgent) {
}
