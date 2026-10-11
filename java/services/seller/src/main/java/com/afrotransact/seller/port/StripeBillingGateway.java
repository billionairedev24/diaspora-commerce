package com.afrotransact.seller.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.StripePriceFacts;

/**
 * Stripe Billing: customers, payment methods, plan subscriptions, the plan catalogue's Products and Prices (created
 * by the seller service from our own catalogue), and the credits, free periods and discounts AfroTransact grants.
 */
public interface StripeBillingGateway {

    String createCustomer(String orgId, String name, String email, String idempotencyKey);

    void attachPaymentMethod(String customerId, String paymentMethodId);

    void setDefaultPaymentMethod(String customerId, String paymentMethodId);

    void detachPaymentMethod(String paymentMethodId);

    /**
     * Creates the subscription on {@code price}; a {@code trialEnd} in the future starts it trialing (no charge until
     * then).
     */
    SubscriptionSnapshot createSubscription(String customerId, PlanPrice price, Instant trialEnd,
                                            String idempotencyKey);

    /** Swaps the subscription item to another price; {@code prorateNow} invoices the proration immediately. */
    SubscriptionSnapshot changePrice(String subscriptionId, String itemId, PlanPrice price, boolean prorateNow,
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
    long previewProratedChange(String customerId, String subscriptionId, String itemId, String priceId);

    // ── Plan catalogue sync ──────────────────────────────────────────────────────────────────────────────────────

    /** Creates the plan's Product ({@code metadata[plan_code]}). */
    String createProduct(String planCode, String name, String idempotencyKey);

    /** Products are not prices: their name may change in place. */
    void updateProduct(String productId, String name);

    /** The active Price holding {@code lookupKey}, if any. */
    Optional<StripePriceFacts> findPriceByLookupKey(String lookupKey);

    Optional<StripePriceFacts> retrievePrice(String priceId);

    /**
     * Creates a recurring USD Price every {@code cadence.months()} months with {@code lookupKey}, moving the key from
     * any older Price ({@code transfer_lookup_key}); metadata carries plan code, version and cadence.
     */
    String createPrice(String productId, long amountCents, Cadence cadence, String lookupKey, String planCode,
                       int planVersion, String idempotencyKey);

    /** Archives a Price ({@code active=false}): existing subscriptions keep renewing on it, new ones can't use it. */
    void archivePrice(String priceId);

    // ── Credits, free periods, discounts ─────────────────────────────────────────────────────────────────────────

    /** Moves the subscription's {@code trial_end} (no proration): nothing is invoiced until then. */
    SubscriptionSnapshot setTrialEnd(String subscriptionId, Instant trialEnd, String idempotencyKey);

    /** A customer balance credit transaction (negative amount) the next invoices use first. */
    BalanceCredit creditBalance(String customerId, long amountCents, String description, String idempotencyKey);

    /** A coupon of {@code percentOff} repeating for {@code durationMonths} months, with our own id. */
    String createCoupon(String couponId, BigDecimal percentOff, long durationMonths, String name,
                        String idempotencyKey);

    /** Adds the coupon to the subscription's discounts, keeping the discounts it already has. */
    SubscriptionSnapshot addDiscount(String subscriptionId, String couponId, String idempotencyKey);

    /** Voids an open invoice (waived: no more payment attempts, nothing owed). */
    InvoiceSnapshot voidInvoice(String invoiceId, String idempotencyKey);

    /** A SetupIntent's id and the client secret the browser / app confirms it with. */
    record SetupIntentSecret(String id, String clientSecret) {
    }

    /** The plan price a subscription item uses, with the ids written to the subscription's metadata. */
    record PlanPrice(String priceId, String planCode, int planVersion, Cadence cadence) {
    }

    /**
     * A balance credit.
     *
     * @param endingBalanceCents the customer's balance afterwards (negative = credit)
     */
    record BalanceCredit(String id, long endingBalanceCents) {
    }
}
