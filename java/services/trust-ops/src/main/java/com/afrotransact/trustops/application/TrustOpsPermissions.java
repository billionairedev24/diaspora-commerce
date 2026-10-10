package com.afrotransact.trustops.application;

/**
 * Permission keys checked by trust-ops use cases (granted by identity-service roles). All but
 * {@link #SELLERS_ENFORCE} already exist in identity's V2__seed_rbac.sql.
 */
public final class TrustOpsPermissions {

    /** Allow or reject listings in the moderation queue. */
    public static final String MODERATE = "catalog:moderate";
    /** Investigate and decide disputes and A-to-z claims. */
    public static final String DISPUTES = "disputes:manage";
    /** Sellers answer claims; agents work support cases. */
    public static final String SUPPORT = "support:respond";
    /** Read, verify and export the audit log. */
    public static final String AUDIT_READ = "audit:read";
    /** View seller health. */
    public static final String ORGS_READ = "orgs:read";
    /** Suspend and reinstate sellers (new key, see README). */
    public static final String SELLERS_ENFORCE = "sellers:enforce";
    /**
     * Publish or reject customer reviews of bookings (bookings-service's key). Deciding a review item here also needs
     * it, so the queue cannot be used to bypass bookings' own check.
     */
    public static final String REVIEWS_MODERATE = "bookings:reviews:moderate";

    private TrustOpsPermissions() {
    }
}
