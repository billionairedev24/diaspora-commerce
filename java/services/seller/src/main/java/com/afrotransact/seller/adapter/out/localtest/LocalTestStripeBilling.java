package com.afrotransact.seller.adapter.out.localtest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.SubscriptionSnapshot;

/**
 * Local stand-in for Stripe Billing (local profile without a Stripe key only; see LocalTestStripePolicy): customers,
 * payment methods and plan subscriptions are accepted with {@code …_local_…} ids, a plan with a trial starts
 * {@code trialing} and one without starts {@code active}, and nothing is charged or sent to Stripe.
 */
public class LocalTestStripeBilling implements StripeBillingGateway {

    private static final Duration MONTH = Duration.ofDays(30);

    private final Clock clock;

    public LocalTestStripeBilling(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String createCustomer(String orgId, String name, String email, String idempotencyKey) {
        return "cus_local_" + stable(orgId);
    }

    @Override
    public void attachPaymentMethod(String customerId, String paymentMethodId) {
        // Accepted: there is no Stripe customer to attach to.
    }

    @Override
    public void setDefaultPaymentMethod(String customerId, String paymentMethodId) {
        // Accepted.
    }

    @Override
    public void detachPaymentMethod(String paymentMethodId) {
        // Accepted.
    }

    @Override
    public SubscriptionSnapshot createSubscription(String customerId, String lookupKey, int trialDays,
                                                   String idempotencyKey) {
        Instant now = Instant.now(clock);
        Instant trialEnd = trialDays > 0 ? now.plus(Duration.ofDays(trialDays)) : null;
        Instant start = trialEnd == null ? now : trialEnd;
        return new SubscriptionSnapshot("sub_local_" + stable(idempotencyKey), customerId,
                trialEnd == null ? "active" : "trialing", "si_local_" + stable(idempotencyKey), lookupKey, trialEnd,
                now, start.plus(MONTH), false);
    }

    @Override
    public SubscriptionSnapshot changePrice(String subscriptionId, String itemId, String lookupKey, boolean prorateNow,
                                            String idempotencyKey) {
        Instant now = Instant.now(clock);
        return new SubscriptionSnapshot(subscriptionId, null, "active", itemId, lookupKey, null, now, now.plus(MONTH),
                false);
    }

    @Override
    public SubscriptionSnapshot setCancelAtPeriodEnd(String subscriptionId, boolean cancel) {
        Instant now = Instant.now(clock);
        return new SubscriptionSnapshot(subscriptionId, null, "active", null, null, null, now, now.plus(MONTH),
                cancel);
    }

    @Override
    public SubscriptionSnapshot cancelNow(String subscriptionId) {
        Instant now = Instant.now(clock);
        return new SubscriptionSnapshot(subscriptionId, null, "canceled", null, null, null, now, now, false);
    }

    @Override
    public void reportMeterEvent(String eventName, String customerId, long value, String identifier,
                                 Instant timestamp) {
        // Usage is recorded by the seller service itself; there is no meter to report to locally.
    }

    @Override
    public String addSubscriptionItem(String subscriptionId, String lookupKey, String idempotencyKey) {
        return "si_local_" + stable(idempotencyKey);
    }

    @Override
    public SetupIntentSecret createSetupIntent(String customerId, String idempotencyKey) {
        String id = "seti_local_" + stable(idempotencyKey);
        return new SetupIntentSecret(id, id + "_secret_local");
    }

    @Override
    public long previewProratedChange(String customerId, String subscriptionId, String itemId, String lookupKey) {
        return 0;
    }

    private static String stable(String seed) {
        String s = seed == null ? UUID.randomUUID().toString() : seed;
        return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 16);
    }
}
