package com.afrotransact.seller.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.PaymentMethod;
import com.afrotransact.seller.port.StripeEvent;

/** Plans & billing for an organisation (Stripe Billing). Only the owner changes billing. */
public interface BillingService {

    BillingOverview overview(String orgId);

    /** Upgrades prorate now; downgrades and cadence changes are recorded and apply at renewal. */
    PlanChangeResult changePlan(String orgId, String planCode, Cadence cadence);

    BillingOverview cancelAtPeriodEnd(String orgId);

    BillingOverview resume(String orgId);

    List<PaymentMethod> paymentMethods(String orgId);

    PaymentMethod addPaymentMethod(String orgId, AddPaymentMethodCommand command);

    List<PaymentMethod> makeDefault(String orgId, UUID paymentMethodId);

    /** The default method can't be removed until another one is the default. */
    List<PaymentMethod> removePaymentMethod(String orgId, UUID paymentMethodId);

    List<Invoice> invoices(String orgId);

    /** After final approval: Stripe customer + subscription with the plan's trial (idempotent; retried by a job). */
    void activate(String orgId);

    /** Job: activations that failed earlier. Returns how many were activated. */
    int retryPendingActivations();

    /** Job: pending downgrades / cadence changes whose renewal has come. Returns how many were applied. */
    int applyDueChanges();

    /** invoice.paid, invoice.payment_failed, customer.subscription.updated / deleted (inside the webhook tx). */
    String handle(StripeEvent event);
}
