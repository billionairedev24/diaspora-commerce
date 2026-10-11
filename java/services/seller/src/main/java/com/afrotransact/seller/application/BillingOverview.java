package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.PaymentMethod;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanQuote;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.SubscriptionTerms;

/**
 * The Billing page: current plan and price, next charge (or trial end), pending change and payment methods.
 *
 * @param nextChargeLabel e.g. "Free trial ends Nov 9 · first charge $139.65" or "Next charge $39 on Nov 9"
 * @param terms           credit balance, trial source and overrides
 */
public record BillingOverview(Subscription subscription, Plan plan, PlanQuote quote, Plan pendingPlan,
                              Instant nextChargeAt, long nextChargeCents, String nextChargeLabel,
                              List<PaymentMethod> paymentMethods, SubscriptionTerms terms) {

    public BillingOverview {
        paymentMethods = List.copyOf(paymentMethods);
    }
}
