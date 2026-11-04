package com.afrotransact.payments.application;

import java.util.List;

/** The shopper's wallet: Stripe Customer and saved payment methods. */
public record Wallet(String stripeCustomerId, List<WalletMethod> items) {
}
