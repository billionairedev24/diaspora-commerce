package com.afrotransact.payments.application;

import java.time.Duration;

/**
 * Operational settings of gift cards (the amount limits and terms are staff settings, {@code MoneySettings}).
 *
 * @param maxFailures        failed redeem attempts one customer may make in {@code window}
 * @param maxFailuresAddress failed attempts one network address may make in {@code window}
 * @param window             the rate-limit window
 * @param deliveryBatch      cards sent per run of the delivery job
 */
public record GiftCardSettings(int maxFailures, int maxFailuresAddress, Duration window, int deliveryBatch) {

    public GiftCardSettings {
        if (maxFailures < 1 || maxFailuresAddress < 1 || window.isNegative() || window.isZero() || deliveryBatch < 1) {
            throw new IllegalArgumentException("Gift card settings must be positive");
        }
    }
}
