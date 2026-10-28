package com.afrotransact.identity.application;

/**
 * Repairs identity's copy of seller and provider organisations from the seller service, the source of truth: the
 * organisation's status, kind and name, and its owners' memberships. Events normally keep the copy current
 * ({@link SellerOrgSyncService}); this catches what an event missed or delivered late, so an approved seller is never
 * left with a PENDING (or missing) organisation and no membership in identity.
 */
public interface OrgReconcileService {

    /** Every organisation the seller service approved or suspended (the scheduled job, on start-up and periodically). */
    OrgReconcileSummary reconcileAll();

    /** {@link #reconcileAll()} on request: a service or staff member with {@code orgs:manage}. */
    OrgReconcileSummary reconcileAllOnRequest();

    /**
     * One organisation on demand (gateway-bff saw the seller service approve it before identity did). The caller must
     * be one of its owners or its approved applicant at the seller service, or a service or staff member with
     * {@code orgs:manage}; anyone else gets 404.
     */
    OrgSyncResult sync(String orgId);

    /** What a reconcile run did. */
    record OrgReconcileSummary(int scanned, int repaired, int failed) {
    }

    /** Identity's copy after an on-demand sync. */
    record OrgSyncResult(String orgId, String status, boolean repaired) {
    }
}
