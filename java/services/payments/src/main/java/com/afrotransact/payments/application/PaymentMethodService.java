package com.afrotransact.payments.application;

import java.util.List;

/**
 * The signed-in shopper's saved payment methods ({@code /v1/customers/me}): a Stripe Customer is created lazily per
 * AfroTransact customer (JWT subject); methods are collected client-side with a SetupIntent and synced by webhooks.
 */
public interface PaymentMethodService {

    Wallet list();

    /** @param types card, us_bank_account, cashapp, link (default: card) */
    SetupIntentResult createSetupIntent(List<String> types, String idempotencyKey);

    /** Stripe API version used for ephemeral keys when the app does not name one. */
    String DEFAULT_EPHEMERAL_KEY_VERSION = "2025-08-27.basil";

    /**
     * PaymentSheet (mobile, S241): an ephemeral key for the shopper's Stripe Customer, created on first use.
     *
     * @param stripeVersion API version of the mobile Stripe SDK (default {@link #DEFAULT_EPHEMERAL_KEY_VERSION})
     */
    EphemeralKeyResult createEphemeralKey(String stripeVersion, String idempotencyKey);

    WalletMethod setDefault(String paymentMethodId);

    /** Detaches a method; the default cannot be removed (payments.default-method-required). */
    void remove(String paymentMethodId);
}
