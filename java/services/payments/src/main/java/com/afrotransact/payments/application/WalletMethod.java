package com.afrotransact.payments.application;

import com.afrotransact.payments.domain.SavedPaymentMethod;

/** A saved payment method and whether it is the shopper's default. */
public record WalletMethod(SavedPaymentMethod method, boolean isDefault) {
}
