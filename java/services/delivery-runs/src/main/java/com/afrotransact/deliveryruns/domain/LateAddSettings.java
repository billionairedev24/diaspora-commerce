package com.afrotransact.deliveryruns.domain;

/**
 * How customer service may take payment for a late order.
 *
 * @param savedCard       charge the customer's saved default card off-session
 * @param paymentLink     send a payment link by SMS
 * @param linkExpiryHours lifetime of the payment link (1–168)
 */
public record LateAddSettings(boolean savedCard, boolean paymentLink, int linkExpiryHours) {

    public static final LateAddSettings DEFAULT = new LateAddSettings(true, true, 24);

    public LateAddSettings {
        if (linkExpiryHours < 1 || linkExpiryHours > 168) {
            throw new RuleViolation("delivery-runs.invalid-payment-settings",
                    "Payment links last between 1 and 168 hours");
        }
    }
}
