package com.afrotransact.seller.port;

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
}
