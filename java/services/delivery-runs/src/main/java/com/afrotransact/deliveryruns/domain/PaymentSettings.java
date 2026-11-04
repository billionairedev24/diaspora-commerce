package com.afrotransact.deliveryruns.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Pre-order payment setup, configured in the back office. Delivery fees are not here: they are the staff-managed
 * {@link DeliveryFeeRule}s (ADR 0005 §3).
 *
 * @param allowedMethods payment methods pre-order checkouts accept (at least one)
 * @param cancelUntil    until when customers cancel for a full refund including the delivery fee
 * @param lateAdd        late-order payment options
 * @param missedDelivery choices offered when a delivery date is missed
 * @param pickupWindow   pickup hours shown to shoppers, e.g. "10am–2pm"
 */
public record PaymentSettings(Set<PaymentMethod> allowedMethods, CancelUntil cancelUntil, LateAddSettings lateAdd,
                              MissedDeliverySettings missedDelivery, String pickupWindow) {

    /** Fees are between 0 and 100.00 in the currency. */
    public static final long MAX_FEE_MINOR = DeliveryFeeRule.MAX_FEE_MINOR;

    public static final PaymentSettings DEFAULT = new PaymentSettings(EnumSet.allOf(PaymentMethod.class),
            CancelUntil.CLOSE, LateAddSettings.DEFAULT, MissedDeliverySettings.DEFAULT, "10am–2pm");

    public PaymentSettings {
        if (allowedMethods == null || allowedMethods.isEmpty()) {
            throw new RuleViolation("delivery-runs.invalid-payment-settings", "Allow at least one payment method");
        }
        allowedMethods = Set.copyOf(allowedMethods);
        cancelUntil = cancelUntil == null ? CancelUntil.CLOSE : cancelUntil;
        lateAdd = lateAdd == null ? LateAddSettings.DEFAULT : lateAdd;
        missedDelivery = missedDelivery == null ? MissedDeliverySettings.DEFAULT : missedDelivery;
        pickupWindow = pickupWindow == null || pickupWindow.isBlank() ? "10am–2pm" : pickupWindow.trim();
        if (pickupWindow.length() > 40) {
            throw new RuleViolation("delivery-runs.invalid-payment-settings", "Pickup window is at most 40 characters");
        }
    }

    /** Validates a single fee (0 to 100.00). */
    public static void requireFee(Money fee) {
        if (fee != null && fee.minor() > MAX_FEE_MINOR) {
            throw new RuleViolation("delivery-runs.invalid-fee", "Fees are between 0 and 100.00");
        }
    }
}
