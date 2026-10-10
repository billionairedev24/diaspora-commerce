package com.afrotransact.seller.port;

import java.time.Instant;

/** Stripe Billing: customers, payment methods and plan subscriptions. */
public interface StripeBillingGateway {

    String createCustomer(String orgId, String name, String email, String idempotencyKey);

    void attachPaymentMethod(String customerId, String paymentMethodId);

    void setDefaultPaymentMethod(String customerId, String paymentMethodId);

    void detachPaymentMethod(String paymentMethodId);

    /** Creates the subscription on the price with {@code lookupKey}, with a free trial when {@code trialDays > 0}. */
    SubscriptionSnapshot createSubscription(String customerId, String lookupKey, int trialDays,
                                            String idempotencyKey);

    /** Swaps the subscription item to another price; {@code prorateNow} invoices the proration immediately. */
    SubscriptionSnapshot changePrice(String subscriptionId, String itemId, String lookupKey, boolean prorateNow,
                                     String idempotencyKey);

    SubscriptionSnapshot setCancelAtPeriodEnd(String subscriptionId, boolean cancel);

    SubscriptionSnapshot cancelNow(String subscriptionId);

    /**
     * Sends one Stripe Billing meter event ({@code payload.stripe_customer_id}, {@code payload.value}); the identifier
     * is also the idempotency key, so a retried send is de-duplicated by Stripe.
     */
    void reportMeterEvent(String eventName, String customerId, long value, String identifier, Instant timestamp);

    /** Adds the metered price with {@code lookupKey} to the subscription; returns the subscription item id. */
    String addSubscriptionItem(String subscriptionId, String lookupKey, String idempotencyKey);

    /**
     * A SetupIntent on the customer for saving a card or bank account for off-session plan charges (Stripe Elements
     * / PaymentSheet in Seller Center); the client confirms it and then registers the resulting {@code pm_…}.
     */
    SetupIntentSecret createSetupIntent(String customerId, String idempotencyKey);

    /**
     * What an immediate prorated price change would invoice now (Stripe invoice preview with {@code always_invoice}),
     * in cents; nothing changes on the subscription.
     */
    long previewProratedChange(String customerId, String subscriptionId, String itemId, String lookupKey);

    /** A SetupIntent's id and the client secret the browser / app confirms it with. */
    record SetupIntentSecret(String id, String clientSecret) {
    }
}
