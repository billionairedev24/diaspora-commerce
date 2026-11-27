package com.afrotransact.seller.adapter.out.stripe;

import java.util.Locale;

import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.PlanPaymentsGateway;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.model.StripeError;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;

/**
 * stripe-java adapter for plan fees (no Stripe Billing): customers, SetupIntents for a card or a US bank account (ACH
 * with Financial Connections: instant verification, microdeposits as the fallback; the Payment Element collects the
 * online mandate) and off-session PaymentIntents limited to {@code card} and {@code us_bank_account}.
 */
public class StripePlanPaymentsAdapter implements PlanPaymentsGateway {

    /** Stripe's decline code for an off-session charge the cardholder must authenticate (3-D Secure). */
    static final String AUTHENTICATION_REQUIRED = "authentication_required";

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
                .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                .addAllowedPaymentMethodType(SetupIntentCreateParams.AllowedPaymentMethodType.CARD)
                .addAllowedPaymentMethodType(SetupIntentCreateParams.AllowedPaymentMethodType.US_BANK_ACCOUNT)
                .setPaymentMethodOptions(SetupIntentCreateParams.PaymentMethodOptions.builder()
                        .setUsBankAccount(SetupIntentCreateParams.PaymentMethodOptions.UsBankAccount.builder()
                                .setVerificationMethod(SetupIntentCreateParams.PaymentMethodOptions.UsBankAccount
                                        .VerificationMethod.AUTOMATIC)
                                .setFinancialConnections(SetupIntentCreateParams.PaymentMethodOptions.UsBankAccount
                                        .FinancialConnections.builder()
                                        .addPermission(SetupIntentCreateParams.PaymentMethodOptions.UsBankAccount
                                                .FinancialConnections.Permission.PAYMENT_METHOD)
                                        .build())
                                .build())
                        .build())
                .putMetadata("purpose", "seller_billing").build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        SetupIntent si = StripeCalls.call("setup intent", () -> client.v1().setupIntents().create(params, options));
        return new SetupIntentSecret(si.getId(), si.getClientSecret());
    }

    @Override
    public SetupResult setupIntent(String setupIntentId) {
        SetupIntent si = StripeCalls.call("setup intent", () -> client.v1().setupIntents().retrieve(setupIntentId));
        return new SetupResult(si.getStatus(), si.getPaymentMethod(), si.getMandate());
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
                .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD)
                .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.US_BANK_ACCOUNT)
                .putMetadata("purpose", "plan_fee").putMetadata("invoice_id", invoiceId).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        try {
            PaymentIntent pi = StripeCalls.call("plan charge", () -> client.v1().paymentIntents().create(params,
                    options));
            return new Charge(status(pi.getStatus()), pi.getId(), null, null, null, paymentMethodId);
        } catch (GatewayException e) {
            if (e.getCause() instanceof CardException card) {
                String ref = card.getStripeError() == null || card.getStripeError().getPaymentIntent() == null ? null
                        : card.getStripeError().getPaymentIntent().getId();
                String decline = card.getDeclineCode() == null ? card.getCode() : card.getDeclineCode();
                // Off-session 3-D Secure: the seller confirms on-session (Plan & billing > Confirm payment).
                Status status = AUTHENTICATION_REQUIRED.equals(decline) || AUTHENTICATION_REQUIRED.equals(card.getCode())
                        ? Status.REQUIRES_ACTION : Status.FAILED;
                return new Charge(status, ref, decline, card.getUserMessage(), null, paymentMethodId);
            }
            throw e;
        }
    }

    @Override
    public Charge paymentStatus(String paymentIntentId) {
        PaymentIntent pi = StripeCalls.call("plan charge status", () -> client.v1().paymentIntents()
                .retrieve(paymentIntentId));
        StripeError err = pi.getLastPaymentError();
        String pm = pi.getPaymentMethod() != null ? pi.getPaymentMethod()
                : err != null && err.getPaymentMethod() != null ? err.getPaymentMethod().getId() : null;
        Status status = status(pi.getStatus());
        if (status == Status.FAILED && err != null && (AUTHENTICATION_REQUIRED.equals(err.getCode())
                || AUTHENTICATION_REQUIRED.equals(err.getDeclineCode()))) {
            status = Status.REQUIRES_ACTION;
        }
        return new Charge(status, pi.getId(), err == null ? null : err.getCode(), err == null ? null : err.getMessage(),
                pi.getClientSecret(), pm);
    }

    @Override
    public RefundResult refund(String paymentIntentId, long amountCents, String reason, String idempotencyKey) {
        RefundCreateParams params = RefundCreateParams.builder().setPaymentIntent(paymentIntentId)
                .setAmount(amountCents).setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                .putMetadata("purpose", "plan_fee").putMetadata("reason", reason == null ? "" : reason).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        Refund r = StripeCalls.call("plan fee refund", () -> client.v1().refunds().create(params, options));
        return new RefundResult(r.getId(), r.getStatus(), r.getAmount() == null ? amountCents : r.getAmount());
    }

    static Status status(String s) {
        return switch (s == null ? "" : s) {
            case "succeeded" -> Status.SUCCEEDED;
            case "processing" -> Status.PROCESSING;
            case "requires_action", "requires_confirmation" -> Status.REQUIRES_ACTION;
            default -> Status.FAILED;
        };
    }
}
