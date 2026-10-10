package com.afrotransact.payments.application;

import java.util.List;

/**
 * The signed-in shopper's saved payment methods ({@code /v1/customers/me}): a Stripe Customer is created lazily per
 * AfroTransact customer (JWT subject); methods are collected client-side with a SetupIntent and synced by webhooks.
 */
public interface PaymentMethodService {

    List<WalletMethod> list();

    /** @param types card, us_bank_account, cashapp, link (default: card) */
    SetupIntentResult createSetupIntent(List<String> types, String idempotencyKey);

    WalletMethod setDefault(String paymentMethodId);

    /** Detaches a method; the default cannot be removed (payments.default-method-required). */
    void remove(String paymentMethodId);
}
