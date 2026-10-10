package com.afrotransact.seller.domain;

/**
 * Step "plan": plan, cadence and the payment method choice. Cards are tokenised by Stripe.js; only brand, last 4 and
 * expiry are stored. {@code US_BANK_ACCOUNT} charges the payout bank by ACH debit.
 */
public record PlanSection(String planCode, Cadence cadence, PaymentMethodKind paymentMethodKind,
                          String stripePaymentMethodId, String cardBrand, String cardLast4, Integer cardExpMonth,
                          Integer cardExpYear) {
}
