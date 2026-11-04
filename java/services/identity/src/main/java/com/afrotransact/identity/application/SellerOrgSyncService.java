package com.afrotransact.identity.application;

/**
 * Mirrors organisations owned by seller-service (topic {@code at.seller-org.events}). Every call is idempotent per
 * event id (platform inbox), so redelivered events are no-ops.
 */
public interface SellerOrgSyncService {

    /**
     * {@code seller.org.approved}: upserts the organisation as APPROVED and makes the applicant and every owner a
     * member with {@code seller.owner} / {@code provider.owner}. Members without an active organisation get this one,
     * so their next token carries the {@code org} claim and owner permissions.
     *
     * @return false when the event was already processed or could not be applied
     */
    boolean approved(SellerOrgChange change);

    /** {@code seller.org.updated}: refreshes name, kind, status and market (memberships are left as they are). */
    boolean updated(SellerOrgChange change);

    /**
     * Repairs identity's copy from the seller service's current state of the organisation (not an event: no inbox):
     * the organisation is created or its status, kind and name corrected, and every owner the seller service lists
     * is made an owner member (the approved applicant too, unless they are already a member with another role). A copy
     * changed after {@code truth.occurredAt()} (a newer event got here first) is left as it is.
     *
     * @return true when anything was repaired
     */
    boolean reconcile(SellerOrgChange truth);
}
