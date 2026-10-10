package com.afrotransact.trustops.application;

/** Event types published through the outbox; aggregate {@code trustops} → topic {@code at.trustops.events}. */
public final class TrustOpsEvents {

    public static final String AGGREGATE = "trustops";

    public static final String MODERATION_QUEUED = "trustops.moderation.queued";
    public static final String MODERATION_ESCALATED = "trustops.moderation.escalated";
    public static final String MODERATION_DECIDED = "trustops.moderation.decided";
    public static final String CLAIM_OPENED = "trustops.claim.opened";
    public static final String CLAIM_SELLER_RESPONDED = "trustops.claim.seller_responded";
    public static final String CLAIM_ESCALATED = "trustops.claim.escalated";
    public static final String CLAIM_DECIDED = "trustops.claim.decided";
    public static final String CLAIM_WITHDRAWN = "trustops.claim.withdrawn";
    public static final String SELLER_SUSPENDED = "trustops.seller.suspended";
    public static final String SELLER_REINSTATED = "trustops.seller.reinstated";
    public static final String SELLER_HEALTH_COMPUTED = "trustops.seller.health_computed";
    public static final String SUPPORT_CASE_OPENED = "trustops.support_case.opened";
    public static final String AUDIT_RECORDED = "trustops.audit.recorded";
    /** Templated notification request for notifications-service. */
    public static final String NOTIFICATION_REQUESTED = "notification.requested";

    /** Inbox consumer names. */
    public static final String CONSUMER_PRODUCTS = "trust-ops:catalog-products";
    public static final String CONSUMER_AUDIT = "trust-ops:audit";
    public static final String CONSUMER_ORDERS = "trust-ops:seller-health";
    public static final String CONSUMER_HANDOFFS = "trust-ops:ai-handoffs";

    private TrustOpsEvents() {
    }
}
