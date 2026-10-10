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

    private IdentityEvents() {
    }
}
