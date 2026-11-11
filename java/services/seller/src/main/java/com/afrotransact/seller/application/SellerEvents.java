package com.afrotransact.seller.application;

/** Event types published by seller-service (contracts/asyncapi/fragments/seller.yaml). */
public final class SellerEvents {

    /** Topic at.seller-application.events, key = application id. */
    public static final String AGGREGATE_APPLICATION = "seller-application";
    /** Topic at.seller-org.events, key = org id. */
    public static final String AGGREGATE_ORG = "seller-org";
    /** Topic at.seller-compliance.events, key = org id (payout and listing enforcement stay in order per org). */
    public static final String AGGREGATE_COMPLIANCE = "seller-compliance";
    /** Topic at.seller-subscription.events, key = org id. */
    public static final String AGGREGATE_SUBSCRIPTION = "seller-subscription";

    public static final String APPLICATION_SUBMITTED = "seller.application.submitted";
    public static final String APPLICATION_FLAGGED = "seller.application.flagged";
    public static final String APPLICATION_INFO_REQUESTED = "seller.application.info_requested";
    public static final String APPLICATION_APPROVED = "seller.application.approved";
    public static final String APPLICATION_REJECTED = "seller.application.rejected";
    /** Internal: a case was assigned, claimed, handed to the approval pool or sent back (staff recipients; never the applicant). */
    public static final String APPLICATION_ASSIGNED = "seller.application.assigned";
    /** Internal: a case reached escalation level 1 or 2; recipients are the escalation targets. */
    public static final String APPLICATION_ESCALATED = "seller.application.escalated";
    public static final String ORG_APPROVED = "seller.org.approved";
    public static final String ORG_UPDATED = "seller.org.updated";
    public static final String COMPLIANCE_CHANGED = "seller.compliance.changed";
    public static final String COMPLIANCE_REMINDER = "seller.compliance.reminder";
    public static final String PAYOUTS_PAUSED = "seller.payouts.paused";
    public static final String PAYOUTS_RESUMED = "seller.payouts.resumed";
    public static final String LISTINGS_HIDDEN = "seller.listings.hidden";
    public static final String LISTINGS_RESTORED = "seller.listings.restored";
    public static final String SUBSCRIPTION_CHANGED = "seller.subscription.changed";
    /** A reward or staff adjustment was applied at Stripe (at.seller-subscription.events); notifications tells the org. */
    public static final String BILLING_GRANT_APPLIED = "seller.billing.grant_applied";
    /** Staff approved or rejected a department request (at.seller-application.events, key = org id; audited by trust-ops). */
    public static final String DEPARTMENT_REQUEST_DECIDED = "seller.department_request.decided";
    /** Staff replaced an organisation's departments with a reason (at.seller-application.events, key = org id; audited by trust-ops). */
    public static final String DEPARTMENTS_CHANGED = "seller.org.departments_changed";
    /** S255: an AfroTransact Delivery pickup location was enabled, changed, disabled or deleted (at.seller-org.events). */
    public static final String PICKUP_LOCATION_UPDATED = "seller.pickup_location.updated";

    /** EnforcementPayload reason while a Trust &amp; Safety suspension holds payouts and hides listings. */
    public static final String REASON_SUSPENDED = "SUSPENDED";
    /** EnforcementPayload reason when a reinstatement releases payouts / listings (no compliance hold remains). */
    public static final String REASON_REINSTATED = "REINSTATED";

    private SellerEvents() {
    }
}
