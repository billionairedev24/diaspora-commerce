package com.afrotransact.deliveryruns.domain;

/**
 * How customer service may take payment for a late order.
 *
 * @param savedCard       charge the customer's saved default card off-session
 * @param paymentLink     send a payment link by SMS
 * @param linkExpiryHours lifetime of the payment link, 1–24 hours (orders-service accepts at most 24)
 */
public record LateAddSettings(boolean savedCard, boolean paymentLink, int linkExpiryHours) {

    /** Longest payment link orders-service creates (its late-preorder API accepts 1–24). */
    public static final int MAX_LINK_EXPIRY_HOURS = 24;

    public static final LateAddSettings DEFAULT = new LateAddSettings(true, true, MAX_LINK_EXPIRY_HOURS);

    public LateAddSettings {
        if (linkExpiryHours < 1 || linkExpiryHours > MAX_LINK_EXPIRY_HOURS) {
            throw new RuleViolation("delivery-runs.invalid-payment-settings",
                    "Payment links expire after 1 to " + MAX_LINK_EXPIRY_HOURS
                            + " hours: orders-service cannot create a payment link that lasts longer");
        }
    }
}
