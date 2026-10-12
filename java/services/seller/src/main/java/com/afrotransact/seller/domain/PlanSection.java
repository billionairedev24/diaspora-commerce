package com.afrotransact.seller.domain;

/**
 * Step "plan": plan, cadence and the payment method choice. Cards are tokenised by Stripe.js; only brand, last 4 and
 * expiry are stored. {@code US_BANK_ACCOUNT} charges the payout bank by ACH debit.
 *
 * @param promoCode         a trial campaign's promo code (S295), redeemed when the subscription starts
 * @param planFeeCollection how the plan will be paid (S298); null keeps the platform default
 */
public record PlanSection(String planCode, Cadence cadence, PaymentMethodKind paymentMethodKind,
                          String stripePaymentMethodId, String cardBrand, String cardLast4, Integer cardExpMonth,
                          Integer cardExpYear, String promoCode, PlanFeeCollection planFeeCollection) {

    public PlanSection {
        promoCode = TrialCampaign.normalise(promoCode);
    }

    /** Without a plan fee collection choice (the platform default applies). */
    public PlanSection(String planCode, Cadence cadence, PaymentMethodKind paymentMethodKind,
                       String stripePaymentMethodId, String cardBrand, String cardLast4, Integer cardExpMonth,
                       Integer cardExpYear, String promoCode) {
        this(planCode, cadence, paymentMethodKind, stripePaymentMethodId, cardBrand, cardLast4, cardExpMonth,
                cardExpYear, promoCode, null);
    }

    /** Without a promo code. */
    public PlanSection(String planCode, Cadence cadence, PaymentMethodKind paymentMethodKind,
                       String stripePaymentMethodId, String cardBrand, String cardLast4, Integer cardExpMonth,
                       Integer cardExpYear) {
        this(planCode, cadence, paymentMethodKind, stripePaymentMethodId, cardBrand, cardLast4, cardExpMonth,
                cardExpYear, null);
    }

    /**
     * An autosave of the same plan and cadence that omits the card keeps the stored card (and promo code): changing only
     * how the plan is paid (S298) must not drop the payment method the applicant already added.
     */
    public PlanSection keepingPaymentOf(PlanSection stored) {
        if (stored == null || stripePaymentMethodId != null || stored.stripePaymentMethodId() == null
                || planCode == null || !planCode.equals(stored.planCode()) || cadence != stored.cadence()) {
            return this;
        }
        return new PlanSection(planCode, cadence, stored.paymentMethodKind(), stored.stripePaymentMethodId(),
                stored.cardBrand(), stored.cardLast4(), stored.cardExpMonth(), stored.cardExpYear(),
                promoCode == null ? stored.promoCode() : promoCode, planFeeCollection);
    }
}
