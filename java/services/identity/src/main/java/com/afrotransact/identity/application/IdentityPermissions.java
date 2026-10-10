package com.afrotransact.identity.application;

/** Permission keys checked by identity use cases. */
public final class IdentityPermissions {

    /** Change roles, grants, module access, identity providers; enable/disable users. */
    public static final String ACCESS_ADMIN = "access:admin";
    /** View access control (Product Owner, Auditor). */
    public static final String ACCESS_READ = "access:read";
    /** Create/update seller and provider organisations and their members (seller service). */
    public static final String ORGS_MANAGE = "orgs:manage";
    /** Service lookups of contact details and organisation owners (notifications, seller, orders, bookings). */
    public static final String USERS_READ = "users:read";
    /** The first-party sign-in API (client credentials of gateway-bff). */
    public static final String AUTH_FIRST_PARTY = "auth:first-party";
    /** Use the driver app (needed to register a driver-app push token). */
    public static final String DELIVERY_OPERATE = "delivery:operate";

    private IdentityPermissions() {
    }
}
