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

    /** {@code identity.password-reset.federated}: sign in with the linked provider (display names) instead. */
    public record PasswordResetFederated(Recipient recipient, String userId, String firstName, List<String> providers,
                                         boolean transactional) {
    }

    /** {@code identity.password.changed}: method RESET; sessionsRevoked = apps and devices signed out. */
    public record PasswordChanged(Recipient recipient, String userId, String firstName, String method,
                                  Instant changedAt, int sessionsRevoked, boolean transactional) {
    }

    /**
     * {@code identity.role.granted} / {@code identity.role.revoked}.
     *
     * @param market S248: for a platform (staff) role, the market it is scoped to (ISO 3166-1 alpha-2; null =
     *               platform-wide), so authz-sync writes {@code market:<code>} grants; for an organisation role, the
     *               organisation's market (informational)
     */
    public record RoleChanged(String userId, String roleKey, String orgId, String actorId, String reason,
                              Instant at, String market) {

        /** A role change without a market (platform-wide staff role, account role). */
        public RoleChanged(String userId, String roleKey, String orgId, String actorId, String reason, Instant at) {
            this(userId, roleKey, orgId, actorId, reason, at, null);
        }
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

    /**
     * {@code identity.sign-in.locked}: too many failed sign-in attempts locked an account (key = user id) or a client
     * IP address (key = {@code ip:<address>}) until {@code lockedUntil}.
     *
     * @param scope   ACCOUNT or IP
     * @param channel {@code first-party} (BFF sign-in API) or {@code hosted} (authorization server pages)
     */
    public record SignInLocked(String scope, String userId, String ip, String channel, int lockouts,
                               long lockoutSeconds, Instant lockedUntil, Instant at) {
    }

    /**
     * {@code identity.device.registered} / {@code identity.device.unregistered}: a push token was added to or removed
     * from an account (the token itself is not published; read it with the contact lookup).
     *
     * @param app      customer or driver
     * @param platform ios, android or web
     * @param provider fcm or apns
     */
    public record DeviceChanged(String userId, String deviceId, String app, String platform, String provider,
                                Instant at) {
    }

    /** {@code identity.notification-preferences.updated}: account-level channel switches changed. */
    public record NotificationPreferencesUpdated(String userId, boolean email, boolean sms, boolean push,
                                                 boolean marketingOptIn, String locale, String timeZone, Instant at) {
    }

    /** {@code identity.user.phone-verified}: the account's phone number was confirmed with an SMS code (S234). */
    public record UserPhoneVerified(String userId, String phone, Instant verifiedAt) {
    }

    /**
     * {@code identity.invitation.created}: notifications-service emails {@code recipient.email} a link to join the
     * organisation. Contains the plain single-use token (7 days); never log the payload.
     *
     * @param acceptUrl  Seller Center link carrying the token
     * @param invitedBy  subject of the inviter (user id or client id)
     */
    public record InvitationCreated(String invitationId, String orgId, String orgName, String orgKind, String email,
                                    String roleKey, String roleName, String token, String acceptUrl,
                                    Instant expiresAt, long expiresInDays, String invitedBy, String inviterName,
                                    Recipient recipient, boolean transactional) {
    }

    /** {@code identity.invitation.revoked}: an open invitation was withdrawn (or replaced by a new one). */
    public record InvitationRevoked(String invitationId, String orgId, String email, String roleKey, String actorId,
                                    String reason, Instant at) {
    }

    /** {@code identity.invitation.accepted}: the invitee joined (signed in, or signed up from the link). */
    public record InvitationAccepted(String invitationId, String orgId, String email, String roleKey, String userId,
                                     boolean alreadyMember, Instant at) {
    }
}
