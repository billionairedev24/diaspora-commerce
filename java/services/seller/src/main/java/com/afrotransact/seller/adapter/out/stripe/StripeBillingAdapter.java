package com.afrotransact.seller.adapter.out.stripe;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.SubscriptionSnapshot;
import com.stripe.StripeClient;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.Price;
import com.stripe.model.SetupIntent;
import com.stripe.model.StripeCollection;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.InvoiceCreatePreviewParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.PriceListParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.billing.MeterEventCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionItemCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import tools.jackson.databind.json.JsonMapper;

/** stripe-java adapter for Billing. Prices are resolved by lookup key (cached), so price ids never live in config. */
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
    public SubscriptionSnapshot createSubscription(String customerId, String lookupKey, int trialDays,
                                                   String idempotencyKey) {
        SubscriptionCreateParams.Builder b = SubscriptionCreateParams.builder().setCustomer(customerId)
                .addItem(SubscriptionCreateParams.Item.builder().setPrice(price(lookupKey)).build())
                .putMetadata("plan_lookup_key", lookupKey);
        if (trialDays > 0) {
            b.setTrialPeriodDays((long) trialDays);
        }
        SubscriptionCreateParams params = b.build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        return snapshot(StripeCalls.call("subscription", () -> client.v1().subscriptions().create(params, options)));
    }

    @Override
    public SubscriptionSnapshot changePrice(String subscriptionId, String itemId, String lookupKey,
                                            boolean prorateNow, String idempotencyKey) {
        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                .addItem(SubscriptionUpdateParams.Item.builder().setId(itemId).setPrice(price(lookupKey)).build())
                .setProrationBehavior(prorateNow ? SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE
                        : SubscriptionUpdateParams.ProrationBehavior.NONE)
                .putMetadata("plan_lookup_key", lookupKey)
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
    public long previewProratedChange(String customerId, String subscriptionId, String itemId, String lookupKey) {
        InvoiceCreatePreviewParams params = InvoiceCreatePreviewParams.builder().setCustomer(customerId)
                .setSubscription(subscriptionId)
                .setSubscriptionDetails(InvoiceCreatePreviewParams.SubscriptionDetails.builder()
                        .addItem(InvoiceCreatePreviewParams.SubscriptionDetails.Item.builder().setId(itemId)
                                .setPrice(price(lookupKey)).build())
                        .setProrationBehavior(InvoiceCreatePreviewParams.SubscriptionDetails.ProrationBehavior
                                .ALWAYS_INVOICE)
                        .build())
                .build();
        Invoice preview = StripeCalls.call("proration preview", () -> client.v1().invoices().createPreview(params));
        return preview.getAmountDue() == null ? 0 : preview.getAmountDue();
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
