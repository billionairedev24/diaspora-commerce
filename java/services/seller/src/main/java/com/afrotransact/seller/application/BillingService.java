package com.afrotransact.seller.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.PaymentMethod;

/**
 * Plans &amp; billing for an organisation (locally scheduled via rrule; Stripe Payments collects the charge).
 * Only the owner changes billing.
 */
public interface BillingService {

    BillingOverview overview(String orgId);

    /** Upgrades prorate now; downgrades and cadence changes are recorded and apply at renewal. */
    PlanChangeResult changePlan(String orgId, String planCode, Cadence cadence);

    BillingOverview cancelAtPeriodEnd(String orgId);

    BillingOverview resume(String orgId);

    List<PaymentMethod> paymentMethods(String orgId);

    /**
     * Starts adding a card or US bank account: a Stripe SetupIntent on the organisation's customer (created on first
     * use). The client confirms it with Stripe.js / PaymentSheet, then registers the resulting {@code pm_…}.
     */
    SetupIntentView setupIntent(String orgId);

    PaymentMethod addPaymentMethod(String orgId, AddPaymentMethodCommand command);

    List<PaymentMethod> makeDefault(String orgId, UUID paymentMethodId);

    /** The default method can't be removed until another one is the default. */
    List<PaymentMethod> removePaymentMethod(String orgId, UUID paymentMethodId);

    List<Invoice> invoices(String orgId);

    /** One invoice (same readers as {@link #invoices}), e.g. to show or print it. */
    Invoice invoice(String orgId, String invoiceId);

    /** The effect of a plan / cadence change without making it (owner or finance; staff with seller:billing). */
    PlanChangePreview previewPlanChange(String orgId, String planCode, Cadence cadence);

    /** After final approval: subscription record with the plan's trial period (idempotent; retried by a job). */
    void activate(String orgId);

    /** Job: activations that failed earlier. Returns how many were activated. */
    int retryPendingActivations();

    /** Who invoices are addressed to (the organisation's name). */
    String billedTo(String orgId);

    /** Retries an uncollectible invoice (after the seller fixed their payment method); owner or finance. */
    Invoice retryInvoice(String orgId, String invoiceId);

    /** Job: pending downgrades / cadence changes whose renewal has come. Returns how many were applied. */
    int applyDueChanges();

    /**
     * Job: subscriptions whose current period has ended — rolls the period forward one cadence occurrence and
     * triggers charge collection. Returns how many were renewed.
     */
    int renewDueSubscriptions();

    /** Staff per-seller billing panel (seller:billing, billing:adjust or plans:manage). */
    BillingPanel panel(String orgId);
}
