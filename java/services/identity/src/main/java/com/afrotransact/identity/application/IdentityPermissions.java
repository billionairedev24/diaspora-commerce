package com.afrotransact.identity.application;

/** Permission keys checked by identity use cases. */
public final class IdentityPermissions {

    /** Change roles, grants, module access, identity providers; enable/disable users. */
    public static final String ACCESS_ADMIN = "access:admin";
    /** View access control (Product Owner, Auditor). */
    public static final String ACCESS_READ = "access:read";
    /** Create/update seller and provider organisations and their members (seller service). */
    public static final String ORGS_MANAGE = "orgs:manage";

    private IdentityPermissions() {
    }
}
