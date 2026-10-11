package com.afrotransact.seller.adapter.out.stripe;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.StripePriceFacts;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.SubscriptionSnapshot;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Coupon;
import com.stripe.model.Customer;
import com.stripe.model.CustomerBalanceTransaction;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.SetupIntent;
import com.stripe.model.StripeCollection;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.RequestOptions;
import com.stripe.param.CouponCreateParams;
import com.stripe.param.CustomerBalanceTransactionCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.InvoiceCreatePreviewParams;
import com.stripe.param.InvoiceVoidInvoiceParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceListParams;
import com.stripe.param.PriceUpdateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.ProductUpdateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionItemCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.billing.MeterEventCreateParams;
import tools.jackson.databind.json.JsonMapper;

/**
 * stripe-java adapter for Billing. Plan prices are created by the seller service from its own catalogue (ids stored
 * per plan version); metered usage prices are still resolved by lookup key (cached).
 */
public class StripeBillingAdapter implements StripeBillingGateway {

    private final StripeClient client;
    private final StripeJson json;
    private final Map<String, String> priceIds = new ConcurrentHashMap<>();

    public StripeBillingAdapter(StripeClient client, JsonMapper mapper) {
        this.client = client;
        this.json = new StripeJson(mapper);
    }

    @Override
    public String createCustomer(String orgId, String name, String email, String idempotencyKey) {
        CustomerCreateParams params = CustomerCreateParams.builder().setName(name).setEmail(email)
                .putMetadata("org_id", orgId).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        Customer c = StripeCalls.call("customer creation", () -> client.v1().customers().create(params, options));
        return c.getId();
    }

    @Override
    public void attachPaymentMethod(String customerId, String paymentMethodId) {
        PaymentMethodAttachParams params = PaymentMethodAttachParams.builder().setCustomer(customerId).build();
        StripeCalls.call("payment method", () -> client.v1().paymentMethods().attach(paymentMethodId, params));
    }

    @Override
    public void setDefaultPaymentMethod(String customerId, String paymentMethodId) {
        CustomerUpdateParams params = CustomerUpdateParams.builder()
                .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                        .setDefaultPaymentMethod(paymentMethodId).build())
                .build();
        StripeCalls.call("default payment method", () -> client.v1().customers().update(customerId, params));
    }

    @Override
    public void detachPaymentMethod(String paymentMethodId) {
        StripeCalls.call("payment method removal", () -> client.v1().paymentMethods().detach(paymentMethodId));
    }

    @Override
    public SubscriptionSnapshot createSubscription(String customerId, PlanPrice price, Instant trialEnd,
                                                   String idempotencyKey) {
        SubscriptionCreateParams.Builder b = SubscriptionCreateParams.builder().setCustomer(customerId)
                .addItem(SubscriptionCreateParams.Item.builder().setPrice(price.priceId()).build())
                .putMetadata("plan_code", price.planCode())
                .putMetadata("plan_version", Integer.toString(price.planVersion()))
                .putMetadata("cadence", price.cadence().name());
        if (trialEnd != null) {
            b.setTrialEnd(trialEnd.getEpochSecond());
        }
        SubscriptionCreateParams params = b.build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return snapshot(StripeCalls.call("subscription", () -> client.v1().subscriptions().create(params, options)));
    }

    @Override
    public SubscriptionSnapshot changePrice(String subscriptionId, String itemId, PlanPrice price,
                                            boolean prorateNow, String idempotencyKey) {
        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                .addItem(SubscriptionUpdateParams.Item.builder().setId(itemId).setPrice(price.priceId()).build())
                .setProrationBehavior(prorateNow ? SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE
                        : SubscriptionUpdateParams.ProrationBehavior.NONE)
                .putMetadata("plan_code", price.planCode())
                .putMetadata("plan_version", Integer.toString(price.planVersion()))
                .putMetadata("cadence", price.cadence().name())
                .build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return snapshot(StripeCalls.call("plan change",
                () -> client.v1().subscriptions().update(subscriptionId, params, options)));
    }

    @Override
    public SubscriptionSnapshot setCancelAtPeriodEnd(String subscriptionId, boolean cancel) {
        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(cancel).build();
        return snapshot(StripeCalls.call("cancellation", () -> client.v1().subscriptions().update(subscriptionId,
                params)));
    }

    @Override
    public SubscriptionSnapshot cancelNow(String subscriptionId) {
        return snapshot(StripeCalls.call("cancellation", () -> client.v1().subscriptions().cancel(subscriptionId)));
    }

    @Override
    public void reportMeterEvent(String eventName, String customerId, long value, String identifier,
                                 Instant timestamp) {
        MeterEventCreateParams params = MeterEventCreateParams.builder().setEventName(eventName)
                .setIdentifier(identifier).setTimestamp(timestamp.getEpochSecond())
                .putPayload("stripe_customer_id", customerId).putPayload("value", Long.toString(value))
                .build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey("meter-" + identifier).build();
        StripeCalls.call("meter event", () -> client.v1().billing().meterEvents().create(params, options));
    }

    @Override
    public String addSubscriptionItem(String subscriptionId, String lookupKey, String idempotencyKey) {
        SubscriptionItemCreateParams params = SubscriptionItemCreateParams.builder().setSubscription(subscriptionId)
                .setPrice(price(lookupKey)).putMetadata("metered_lookup_key", lookupKey).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        SubscriptionItem item = StripeCalls.call("metered subscription item",
                () -> client.v1().subscriptionItems().create(params, options));
        return item.getId();
    }

    @Override
    public SetupIntentSecret createSetupIntent(String customerId, String idempotencyKey) {
        SetupIntentCreateParams params = SetupIntentCreateParams.builder().setCustomer(customerId)
                .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION).putMetadata("purpose", "seller_billing").build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        SetupIntent si = StripeCalls.call("setup intent", () -> client.v1().setupIntents().create(params, options));
        return new SetupIntentSecret(si.getId(), si.getClientSecret());
    }

    @Override
    public long previewProratedChange(String customerId, String subscriptionId, String itemId, String priceId) {
        InvoiceCreatePreviewParams params = InvoiceCreatePreviewParams.builder().setCustomer(customerId)
                .setSubscription(subscriptionId)
                .setSubscriptionDetails(InvoiceCreatePreviewParams.SubscriptionDetails.builder()
                        .addItem(InvoiceCreatePreviewParams.SubscriptionDetails.Item.builder().setId(itemId)
                                .setPrice(priceId).build())
                        .setProrationBehavior(InvoiceCreatePreviewParams.SubscriptionDetails.ProrationBehavior
                                .ALWAYS_INVOICE)
                        .build())
                .build();
        Invoice preview = StripeCalls.call("proration preview", () -> client.v1().invoices().createPreview(params));
        return preview.getAmountDue() == null ? 0 : preview.getAmountDue();
    }

    @Override
    public String createProduct(String planCode, String name, String idempotencyKey) {
        ProductCreateParams params = ProductCreateParams.builder().setName(name).putMetadata("plan_code", planCode)
                .build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        Product p = StripeCalls.call("plan product", () -> client.v1().products().create(params, options));
        return p.getId();
    }

    @Override
    public void updateProduct(String productId, String name) {
        ProductUpdateParams params = ProductUpdateParams.builder().setName(name).build();
        StripeCalls.call("plan product", () -> client.v1().products().update(productId, params));
    }

    @Override
    public Optional<StripePriceFacts> findPriceByLookupKey(String lookupKey) {
        PriceListParams params = PriceListParams.builder().addLookupKey(lookupKey).setActive(true).setLimit(1L)
                .build();
        StripeCollection<Price> prices = StripeCalls.call("price lookup", () -> client.v1().prices().list(params));
        if (prices.getData() == null || prices.getData().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(json.price(json.tree(prices.getData().getFirst().toJson())));
    }

    @Override
    public Optional<StripePriceFacts> retrievePrice(String priceId) {
        try {
            Price p = client.v1().prices().retrieve(priceId);
            return Optional.of(json.price(json.tree(p.getLastResponse().body())));
        } catch (InvalidRequestException e) {
            if ("resource_missing".equals(e.getCode())) {
                return Optional.empty();
            }
            throw new GatewayException("seller.stripe-rejected", "Stripe rejected price lookup: "
                    + e.getUserMessage(), true, e);
        } catch (StripeException e) {
            throw new GatewayException("seller.stripe-unavailable", "Stripe is unavailable (price lookup); retry",
                    false, e);
        }
    }

    @Override
    public String createPrice(String productId, long amountCents, Cadence cadence, String lookupKey, String planCode,
                              int planVersion, String idempotencyKey) {
        PriceCreateParams params = PriceCreateParams.builder().setProduct(productId).setCurrency("usd")
                .setUnitAmount(amountCents)
                .setRecurring(PriceCreateParams.Recurring.builder()
                        .setInterval(PriceCreateParams.Recurring.Interval.MONTH)
                        .setIntervalCount((long) cadence.months()).build())
                .setLookupKey(lookupKey).setTransferLookupKey(true)
                .putMetadata("plan_code", planCode).putMetadata("plan_version", Integer.toString(planVersion))
                .putMetadata("cadence", cadence.name())
                .build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        Price p = StripeCalls.call("plan price", () -> client.v1().prices().create(params, options));
        return p.getId();
    }

    @Override
    public void archivePrice(String priceId) {
        PriceUpdateParams params = PriceUpdateParams.builder().setActive(false).build();
        StripeCalls.call("price archive", () -> client.v1().prices().update(priceId, params));
    }

    @Override
    public SubscriptionSnapshot setTrialEnd(String subscriptionId, Instant trialEnd, String idempotencyKey) {
        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder().setTrialEnd(trialEnd.getEpochSecond())
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return snapshot(StripeCalls.call("trial end",
                () -> client.v1().subscriptions().update(subscriptionId, params, options)));
    }

    @Override
    public BalanceCredit creditBalance(String customerId, long amountCents, String description,
                                       String idempotencyKey) {
        CustomerBalanceTransactionCreateParams params = CustomerBalanceTransactionCreateParams.builder()
                .setAmount(-amountCents).setCurrency("usd").setDescription(description).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        CustomerBalanceTransaction t = StripeCalls.call("balance credit",
                () -> client.v1().customers().balanceTransactions().create(customerId, params, options));
        return new BalanceCredit(t.getId(), t.getEndingBalance() == null ? -amountCents : t.getEndingBalance());
    }

    @Override
    public String createCoupon(String couponId, BigDecimal percentOff, long durationMonths, String name,
                               String idempotencyKey) {
        CouponCreateParams params = CouponCreateParams.builder().setId(couponId).setPercentOff(percentOff)
                .setDuration(CouponCreateParams.Duration.REPEATING).setDurationInMonths(durationMonths)
                .setName(name).setMaxRedemptions(1L).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        Coupon c = StripeCalls.call("coupon", () -> client.v1().coupons().create(params, options));
        return c.getId();
    }

    @Override
    public SubscriptionSnapshot addDiscount(String subscriptionId, String couponId, String idempotencyKey) {
        Subscription current = StripeCalls.call("subscription", () -> client.v1().subscriptions()
                .retrieve(subscriptionId));
        SubscriptionUpdateParams.Builder b = SubscriptionUpdateParams.builder();
        List<String> existing = current.getDiscounts() == null ? List.of() : current.getDiscounts();
        for (String d : existing) {
            b.addDiscount(SubscriptionUpdateParams.Discount.builder().setDiscount(d).build());
        }
        b.addDiscount(SubscriptionUpdateParams.Discount.builder().setCoupon(couponId).build());
        SubscriptionUpdateParams params = b.build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return snapshot(StripeCalls.call("discount",
                () -> client.v1().subscriptions().update(subscriptionId, params, options)));
    }

    @Override
    public InvoiceSnapshot voidInvoice(String invoiceId, String idempotencyKey) {
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        Invoice i = StripeCalls.call("invoice void", () -> client.v1().invoices().voidInvoice(invoiceId,
                InvoiceVoidInvoiceParams.builder().build(), options));
        return json.invoice(json.tree(i.getLastResponse().body()));
    }

    private String price(String lookupKey) {
        String cached = priceIds.get(lookupKey);
        if (cached != null) {
            return cached;
        }
        PriceListParams params = PriceListParams.builder().addLookupKey(lookupKey).setActive(true).setLimit(1L)
                .build();
        StripeCollection<Price> prices = StripeCalls.call("price lookup", () -> client.v1().prices().list(params));
        if (prices.getData() == null || prices.getData().isEmpty()) {
            throw new GatewayException("seller.stripe-price-missing", "No active Stripe price with lookup key "
                    + lookupKey, false, null);
        }
        String id = prices.getData().getFirst().getId();
        priceIds.put(lookupKey, id);
        return id;
    }

    private SubscriptionSnapshot snapshot(Subscription s) {
        return json.subscription(json.tree(s.getLastResponse().body()));
    }
}
