package com.afrotransact.seller.application;

import com.afrotransact.seller.domain.PaymentMethodKind;

/** A payment method tokenised client-side (Stripe.js / Financial Connections); we keep display fields only. */
public record AddPaymentMethodCommand(String stripePaymentMethodId, PaymentMethodKind kind, String brand,
                                      String last4, Integer expMonth, Integer expYear, String bankName,
                                      String accountType, boolean makeDefault) {
}
