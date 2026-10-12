package com.afrotransact.seller.adapter.out.localtest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.StripePriceFacts;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.SubscriptionSnapshot;

/**
 * Local stand-in for Stripe Billing (local profile without a Stripe key only; see LocalTestStripePolicy). Customers,
 * payment methods and plan subscriptions are accepted with {@code …_local_…} ids; a subscription with a trial starts
 * {@code trialing}, one without starts {@code active}. The catalogue sync, free days, credits and coupons behave like
 * Stripe in memory (products and prices keep their amounts so the drift report is in sync, {@code trial_end} moves,
 * the customer balance adds up, a coupon is remembered on the subscription), and nothing is charged or sent to Stripe.
 * State is lost on restart; the seller service's own tables keep what matters.
 */
public class LocalTestStripeBilling implements StripeBillingGateway {

    private static final Duration MONTH = Duration.ofDays(30);

    private final Clock clock;
    private final Map<String, StripePriceFacts> prices = new ConcurrentHashMap<>();
    private final Map<String, SubscriptionSnapshot> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Map<String, List<String>> discounts = new ConcurrentHashMap<>();

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
    public SubscriptionSnapshot createSubscription(String customerId, PlanPrice price, Instant trialEnd,
                                                   String idempotencyKey) {
        Instant now = Instant.now(clock);
        Instant trial = trialEnd != null && trialEnd.isAfter(now) ? trialEnd : null;
        Instant start = trial == null ? now : trial;
        String id = "sub_local_" + stable(idempotencyKey);
        return remember(new SubscriptionSnapshot(id, customerId, trial == null ? "active" : "trialing",
                "si_local_" + stable(idempotencyKey), price.priceId(), null, trial, now,
                start.plus(period(price.cadence())), false));
    }

    @Override
    public SubscriptionSnapshot changePrice(String subscriptionId, String itemId, PlanPrice price, boolean prorateNow,
                                            String idempotencyKey) {
        SubscriptionSnapshot s = current(subscriptionId);
        return remember(new SubscriptionSnapshot(subscriptionId, s.customerId(), s.status(), itemId, price.priceId(),
                null, s.trialEnd(), s.currentPeriodStart(), s.currentPeriodEnd(), s.cancelAtPeriodEnd()));
    }

    @Override
    public SubscriptionSnapshot setCancelAtPeriodEnd(String subscriptionId, boolean cancel) {
        SubscriptionSnapshot s = current(subscriptionId);
        return remember(new SubscriptionSnapshot(subscriptionId, s.customerId(), s.status(), s.itemId(), s.priceId(),
                null, s.trialEnd(), s.currentPeriodStart(), s.currentPeriodEnd(), cancel));
    }

    @Override
    public SubscriptionSnapshot cancelNow(String subscriptionId) {
        Instant now = Instant.now(clock);
        SubscriptionSnapshot s = current(subscriptionId);
        return remember(new SubscriptionSnapshot(subscriptionId, s.customerId(), "canceled", s.itemId(), s.priceId(),
                null, null, now, now, false));
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
    public long previewProratedChange(String customerId, String subscriptionId, String itemId, String priceId) {
        return 0;
    }

    @Override
    public String createProduct(String planCode, String name, String idempotencyKey) {
        return "prod_local_" + stable(idempotencyKey);
    }

    @Override
    public void updateProduct(String productId, String name) {
        // Accepted.
    }

    @Override
    public Optional<StripePriceFacts> findPriceByLookupKey(String lookupKey) {
        return prices.values().stream().filter(p -> p.active() && lookupKey.equals(p.lookupKey())).findFirst();
    }

    @Override
    public Optional<StripePriceFacts> retrievePrice(String priceId) {
        return Optional.ofNullable(prices.get(priceId));
    }

    @Override
    public String createPrice(String productId, long amountCents, Cadence cadence, String lookupKey, String planCode,
                              int planVersion, String idempotencyKey) {
        String id = "price_local_" + stable(idempotencyKey);
        // transfer_lookup_key: the key moves to the new price.
        prices.replaceAll((k, p) -> lookupKey.equals(p.lookupKey()) && !k.equals(id) ? new StripePriceFacts(p.id(),
                p.productId(), p.amountCents(), p.currency(), p.intervalMonths(), p.active(), null) : p);
        prices.put(id, new StripePriceFacts(id, productId, amountCents, "usd", cadence.months(), true, lookupKey));
        return id;
    }

    @Override
    public void archivePrice(String priceId) {
        prices.computeIfPresent(priceId, (k, p) -> new StripePriceFacts(p.id(), p.productId(), p.amountCents(),
                p.currency(), p.intervalMonths(), false, p.lookupKey()));
    }

    @Override
    public SubscriptionSnapshot setTrialEnd(String subscriptionId, Instant trialEnd, String idempotencyKey) {
        SubscriptionSnapshot s = current(subscriptionId);
        return remember(new SubscriptionSnapshot(subscriptionId, s.customerId(), "trialing", s.itemId(), s.priceId(),
                null, trialEnd, s.currentPeriodStart(), trialEnd, s.cancelAtPeriodEnd()));
    }

    @Override
    public BalanceCredit creditBalance(String customerId, long amountCents, String description,
                                       String idempotencyKey) {
        long ending = balances.merge(customerId, -amountCents, Long::sum);
        return new BalanceCredit("cbtxn_local_" + stable(idempotencyKey), ending);
    }

    @Override
    public BalanceCredit debitBalance(String customerId, long amountCents, String description,
                                      String idempotencyKey) {
        long ending = balances.merge(customerId, amountCents, Long::sum);
        return new BalanceCredit("cbtxn_local_" + stable(idempotencyKey), ending);
    }

    /** Local stand-in: invoices stay drafts until a (simulated) webhook says otherwise. */
    @Override
    public InvoiceSnapshot retrieveInvoice(String invoiceId) {
        return new InvoiceSnapshot(invoiceId, null, null, null, "draft", "subscription_cycle", 0, 0, "usd", null, null,
                null, null, 0, null, Instant.now(clock), List.of(), null);
    }

    @Override
    public String createCoupon(String couponId, BigDecimal percentOff, long durationMonths, String name,
                               String idempotencyKey) {
        return couponId;
    }

    @Override
    public SubscriptionSnapshot addDiscount(String subscriptionId, String couponId, String idempotencyKey) {
        discounts.computeIfAbsent(subscriptionId, k -> new ArrayList<>()).add(couponId);
        return current(subscriptionId);
    }

    @Override
    public InvoiceSnapshot voidInvoice(String invoiceId, String idempotencyKey) {
        return new InvoiceSnapshot(invoiceId, null, null, null, "void", null, 0, 0, "usd", null, null, null, null, 0,
                null, Instant.now(clock), List.of(), null);
    }

    private SubscriptionSnapshot current(String id) {
        Instant now = Instant.now(clock);
        return subscriptions.getOrDefault(id, new SubscriptionSnapshot(id, null, "active", null, null, null, null, now,
                now.plus(MONTH), false));
    }

    private SubscriptionSnapshot remember(SubscriptionSnapshot s) {
        subscriptions.put(s.id(), s);
        return s;
    }

    private static Duration period(Cadence cadence) {
        return Duration.ofDays(30L * cadence.months());
    }

    private static String stable(String seed) {
        String s = seed == null ? UUID.randomUUID().toString() : seed;
        return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 16);
    }
}
