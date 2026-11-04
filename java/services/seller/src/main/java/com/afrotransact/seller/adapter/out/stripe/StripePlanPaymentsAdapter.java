package com.afrotransact.seller.adapter.out.stripe;

import java.util.Locale;

import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.PlanPaymentsGateway;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.SetupIntentCreateParams;

/** stripe-java adapter for plan fees: customers, SetupIntents and off-session PaymentIntents (no Stripe Billing). */
public class StripePlanPaymentsAdapter implements PlanPaymentsGateway {

    private final StripeClient client;

    public StripePlanPaymentsAdapter(StripeClient client) {
        this.client = client;
    }

    @Override
    public String ensureCustomer(String orgId, String name, String email) {
        CustomerCreateParams params = CustomerCreateParams.builder().setName(name).setEmail(email)
                .putMetadata("org_id", orgId).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey("plan-customer-" + orgId).build();
        Customer c = StripeCalls.call("customer creation", () -> client.v1().customers().create(params, options));
        return c.getId();
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
    public void attachPaymentMethod(String customerId, String paymentMethodId) {
        PaymentMethodAttachParams params = PaymentMethodAttachParams.builder().setCustomer(customerId).build();
        StripeCalls.call("payment method", () -> client.v1().paymentMethods().attach(paymentMethodId, params));
    }

    @Override
    public void detachPaymentMethod(String paymentMethodId) {
        StripeCalls.call("payment method removal", () -> client.v1().paymentMethods().detach(paymentMethodId));
    }

    @Override
    public Charge charge(String customerId, String paymentMethodId, long amountCents, String currency,
                         String invoiceId, String description, String idempotencyKey) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder().setAmount(amountCents)
                .setCurrency(currency.toLowerCase(Locale.ROOT)).setCustomer(customerId)
                .setPaymentMethod(paymentMethodId).setOffSession(true).setConfirm(true).setDescription(description)
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .putMetadata("purpose", "plan_fee").putMetadata("invoice_id", invoiceId).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        try {
            PaymentIntent pi = StripeCalls.call("plan charge", () -> client.v1().paymentIntents().create(params,
                    options));
            return new Charge(status(pi.getStatus()), pi.getId(), null, null);
        } catch (GatewayException e) {
            if (e.getCause() instanceof CardException card) {
                String ref = card.getStripeError() == null || card.getStripeError().getPaymentIntent() == null ? null
                        : card.getStripeError().getPaymentIntent().getId();
                return new Charge(Status.FAILED, ref, card.getCode(), card.getUserMessage());
            }
            throw e;
        }
    }

    private static Status status(String s) {
        return switch (s == null ? "" : s) {
            case "succeeded" -> Status.SUCCEEDED;
            case "processing" -> Status.PROCESSING;
            case "requires_action", "requires_confirmation" -> Status.REQUIRES_ACTION;
            default -> Status.FAILED;
        };
    }
}
