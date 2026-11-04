package com.afrotransact.seller.adapter.out.localtest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.afrotransact.seller.port.PlanPaymentsGateway;

/**
 * Local stand-in for plan-fee payments when there is no Stripe key (local profile only): every charge succeeds unless
 * the payment method id says otherwise ({@code pm_card_chargeCustomerFail} or anything containing "decline").
 */
public class LocalTestPlanPayments implements PlanPaymentsGateway {

    @Override
    public String ensureCustomer(String orgId, String name, String email) {
        return "cus_local_" + orgId;
    }

    @Override
    public SetupIntentSecret createSetupIntent(String customerId, String idempotencyKey) {
        String id = "seti_local_" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        return new SetupIntentSecret(id, id + "_secret_local");
    }

    @Override
    public void attachPaymentMethod(String customerId, String paymentMethodId) {
        // nothing to attach locally
    }

    @Override
    public void detachPaymentMethod(String paymentMethodId) {
        // nothing to detach locally
    }

    @Override
    public Charge charge(String customerId, String paymentMethodId, long amountCents, String currency,
                         String invoiceId, String description, String idempotencyKey) {
        String ref = "pi_local_" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        if (paymentMethodId.contains("chargeCustomerFail") || paymentMethodId.contains("decline")) {
            return new Charge(Status.FAILED, ref, "card_declined", "Your card was declined.");
        }
        return new Charge(Status.SUCCEEDED, ref, null, null);
    }
}
