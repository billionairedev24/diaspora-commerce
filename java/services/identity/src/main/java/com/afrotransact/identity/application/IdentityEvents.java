package com.afrotransact.identity.application;

/** Event types published by the identity service (documented in contracts/asyncapi/fragments/identity.yaml). */
public final class IdentityEvents {

    /**
     * Aggregate type: every identity event goes to Kafka topic {@code at.identity.events}, keyed by user id (or by
     * role key for role-permission changes). The notifications service consumes this topic.
     */
    public static final String AGGREGATE = "identity";

    public static final String USER_REGISTERED = "identity.user.registered";
    public static final String USER_EMAIL_VERIFIED = "identity.user.email-verified";
    public static final String USER_DISABLED = "identity.user.disabled";
    public static final String USER_ENABLED = "identity.user.enabled";
    public static final String VERIFICATION_REQUESTED = "identity.verification.requested";
    public static final String ROLE_GRANTED = "identity.role.granted";
    public static final String ROLE_REVOKED = "identity.role.revoked";
    public static final String PERMISSION_GRANTED = "identity.permission.granted";
    public static final String PERMISSION_REVOKED = "identity.permission.revoked";
    public static final String SIGN_IN_LOCKED = "identity.sign-in.locked";
    public static final String DEVICE_REGISTERED = "identity.device.registered";
    public static final String DEVICE_UNREGISTERED = "identity.device.unregistered";
    public static final String NOTIFICATION_PREFERENCES_UPDATED = "identity.notification-preferences.updated";
    public static final String USER_PHONE_VERIFIED = "identity.user.phone-verified";
    /** Key = organisation id. Carries the plain single-use token for the invitation email. */
    public static final String INVITATION_CREATED = "identity.invitation.created";
    public static final String INVITATION_REVOKED = "identity.invitation.revoked";
    /** Key = the accepting user's id. */
    public static final String INVITATION_ACCEPTED = "identity.invitation.accepted";
    /** S300: a reset was asked for an account without a password (social sign-in only); key = user id. */
    public static final String PASSWORD_RESET_FEDERATED = "identity.password-reset.federated";
    /** S300: the password was reset with a code and every session revoked; key = user id. */
    public static final String PASSWORD_CHANGED = "identity.password.changed";

    private IdentityEvents() {
    }
}
