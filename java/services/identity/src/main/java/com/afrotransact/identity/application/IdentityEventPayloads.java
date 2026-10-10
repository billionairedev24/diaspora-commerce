package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.List;

/** JSON payloads of identity events. Field names are part of the AsyncAPI contract. */
public final class IdentityEventPayloads {

    private IdentityEventPayloads() {
    }

    /** {@code identity.user.registered}. */
    public record UserRegistered(String userId, String email, String firstName, String lastName, String phone,
                                 String intent, List<String> roles, String method, boolean emailVerified,
                                 boolean smsConsent, Instant registeredAt) {
    }

    /** {@code identity.user.email-verified}. */
    public record UserEmailVerified(String userId, String email, Instant verifiedAt) {
    }

    /**
     * {@code identity.verification.requested}: the notifications service renders template
     * {@code identity.verification} on {@code channel} for {@code recipient}. One-time codes are security messages:
     * always sent, quiet hours do not apply.
     *
     * @param channel {@code email} or {@code sms}
     * @param purpose EMAIL_VERIFICATION, SIGN_IN_SMS or SMS_ENROLMENT
     */
    public record VerificationRequested(Recipient recipient, String channel, String code, long expiresInMinutes,
                                        Instant expiresAt, String purpose, String userId, String firstName,
                                        boolean transactional) {
    }

    /** Notification recipient block (notifications-service payload convention); one address is set. */
    public record Recipient(String id, String email, String phone) {
    }

    /** {@code identity.role.granted} / {@code identity.role.revoked}. */
    public record RoleChanged(String userId, String roleKey, String orgId, String actorId, String reason,
                              Instant at) {
    }

    /**
     * {@code identity.permission.granted} / {@code identity.permission.revoked}: either a direct grant to a user
     * ({@code userId}) or a change to a role's permissions ({@code roleKey}).
     */
    public record PermissionChanged(String userId, String roleKey, String permissionKey, String actorId,
                                    String reason, Instant at) {
    }

    /** {@code identity.user.disabled} / {@code identity.user.enabled}. */
    public record UserStatusChanged(String userId, String actorId, String reason, Instant at) {
    }
}
