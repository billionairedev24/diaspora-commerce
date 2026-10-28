package com.afrotransact.seller.application;

/** Permission keys checked by seller use cases (granted by identity-service roles). */
public final class SellerPermissions {

    /** Step 1 of the two-step review: approve, flag or request info; read the queue and dossiers. */
    public static final String APPLICATIONS_REVIEW = "applications:review";
    /** Step 2: final approve, send back to reviewer, reject. */
    public static final String APPLICATIONS_APPROVE = "applications:approve";
    /** See full SSN/EIN and dates of birth in application dossiers (every view is logged). */
    public static final String APPLICATIONS_PII = "applications:pii";
    /** Stripe compliance console: list accounts, send reminders, pause payouts (Ops, Finance). */
    public static final String COMPLIANCE_REMIND = "compliance:remind";
    /** Pause and release payout holds (Trust & Safety). */
    public static final String COMPLIANCE_HOLD = "compliance:hold";
    /**
     * Suspend and reinstate organisations (Trust &amp; Safety staff and the {@code trust-ops-service} client); also
     * reads any organisation's Account health.
     */
    public static final String SELLERS_ENFORCE = "sellers:enforce";
    /** Staff access to any organisation's plan, payment methods and invoices. */
    public static final String BILLING = "seller:billing";
    /** Staff: every organisation's metered usage statements, and re-running metering for a period (S206). */
    public static final String USAGE = "billing:usage";
    /** Staff: plan catalogue (create, version, publish, retire, sync with Stripe), trial campaigns, reward rules. */
    public static final String PLANS_MANAGE = "plans:manage";
    /** Staff: manual credits, free days, waived invoices and trial overrides for an organisation (audited). */
    public static final String BILLING_ADJUST = "billing:adjust";

    private SellerPermissions() {
    }
}
