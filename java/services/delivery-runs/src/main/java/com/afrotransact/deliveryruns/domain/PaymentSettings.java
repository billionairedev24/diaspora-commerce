package com.afrotransact.deliveryruns.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Pre-order payment setup, configured in the back office.
 *
 * @param doorFees       door-delivery fee per currency in minor units (e.g. {@code {USD: 799, CAD: 999}})
 * @param pickupFees     pickup fee per currency in minor units (missing currency = free)
 * @param allowedMethods payment methods pre-order checkouts accept (at least one)
 * @param cancelUntil    until when customers cancel for a full refund including the delivery fee
 * @param lateAdd        late-order payment options
 * @param missedDelivery choices offered when a delivery date is missed
 * @param pickupWindow   pickup hours shown to shoppers, e.g. "10am–2pm"
 */
public record PaymentSettings(Map<String, Long> doorFees, Map<String, Long> pickupFees,
                              Set<PaymentMethod> allowedMethods, CancelUntil cancelUntil, LateAddSettings lateAdd,
                              MissedDeliverySettings missedDelivery, String pickupWindow) {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** Fees are between 0 and 100.00 in the currency. */
    public static final long MAX_FEE_MINOR = 10_000;

    public static final PaymentSettings DEFAULT = new PaymentSettings(Map.of("USD", 799L, "CAD", 999L), Map.of(),
            EnumSet.allOf(PaymentMethod.class), CancelUntil.CLOSE, LateAddSettings.DEFAULT,
            MissedDeliverySettings.DEFAULT, "10am–2pm");

    public PaymentSettings {
        doorFees = fees(doorFees);
        pickupFees = fees(pickupFees);
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

    /** The configured door fee in {@code currency}, if any. */
    public Optional<Money> doorFee(String currency) {
        Long minor = doorFees.get(currency);
        return minor == null ? Optional.empty() : Optional.of(new Money(minor, currency));
    }

    /** The pickup fee in {@code currency} (free when not configured). */
    public Money pickupFee(String currency) {
        return new Money(pickupFees.getOrDefault(currency, 0L), currency);
    }

    /** Validates a single fee (0 to 100.00). */
    public static void requireFee(Money fee) {
        if (fee != null && fee.minor() > MAX_FEE_MINOR) {
            throw new RuleViolation("delivery-runs.invalid-fee", "Fees are between 0 and 100.00");
        }
    }

    private static Map<String, Long> fees(Map<String, Long> in) {
        Map<String, Long> out = new TreeMap<>();
        if (in == null) {
            return Map.of();
        }
        in.forEach((currency, minor) -> {
            if (currency == null || !CURRENCY.matcher(currency).matches()) {
                throw new RuleViolation("delivery-runs.invalid-currency", "Fee currencies are ISO 4217 codes");
            }
            if (minor == null || minor < 0 || minor > MAX_FEE_MINOR) {
                throw new RuleViolation("delivery-runs.invalid-fee", "Fees are between 0 and 100.00");
            }
            out.put(currency, minor);
        });
        return Map.copyOf(out);
    }
}
