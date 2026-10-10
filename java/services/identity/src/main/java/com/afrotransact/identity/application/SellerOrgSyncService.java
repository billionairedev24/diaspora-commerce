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
}
