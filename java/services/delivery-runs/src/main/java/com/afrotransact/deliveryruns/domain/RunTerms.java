package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * What staff schedule for a run.
 *
 * @param window         delivery window
 * @param cutoffAt       last moment to preorder
 * @param opensAt        optional: an ANNOUNCED run opens for orders automatically at this time
 * @param sellerPickupAt optional: when the sweep collects from sellers (between cutoff and the window end)
 * @param feeMinor       run delivery fee in minor units (e.g. 799)
 * @param currency       ISO 4217
 * @param minOrders      minimum orders for the run to go ahead
 * @param capacity       order and/or package limits
 * @param dropMode       doorstep or pickup point
 * @param pickupPoint    required for pickup-point runs
 * @param cutoffPolicy   what happens at cutoff when the minimum is not met
 * @param extendDays     days an EXTEND moves the cutoff and window by (1–30)
 */
public record RunTerms(DeliveryWindow window, Instant cutoffAt, Instant opensAt, Instant sellerPickupAt, long feeMinor,
                       String currency, int minOrders, Capacity capacity, DropMode dropMode, PickupPoint pickupPoint,
                       CutoffPolicy cutoffPolicy, int extendDays) {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public RunTerms {
        if (window == null || cutoffAt == null) {
            throw new RuleViolation("delivery-runs.invalid-window", "Delivery window and cutoff are required");
        }
        if (!cutoffAt.isBefore(window.start())) {
            throw new RuleViolation("delivery-runs.invalid-cutoff", "The order cutoff must be before the delivery window");
        }
        if (opensAt != null && !opensAt.isBefore(cutoffAt)) {
            throw new RuleViolation("delivery-runs.invalid-opens-at", "A run must open before its cutoff");
        }
        if (sellerPickupAt != null && (sellerPickupAt.isBefore(cutoffAt) || sellerPickupAt.isAfter(window.end()))) {
            throw new RuleViolation("delivery-runs.invalid-seller-pickup",
                    "Seller pickup must be between the cutoff and the end of the delivery window");
        }
        if (feeMinor < 0) {
            throw new RuleViolation("delivery-runs.invalid-fee", "The run fee cannot be negative");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new RuleViolation("delivery-runs.invalid-currency", "Currency must be an ISO 4217 code");
        }
        if (minOrders < 1) {
            throw new RuleViolation("delivery-runs.invalid-minimum", "Minimum orders must be at least 1");
        }
        capacity = capacity == null ? Capacity.UNLIMITED : capacity;
        if (capacity.maxOrders() != null && capacity.maxOrders() < minOrders) {
            throw new RuleViolation("delivery-runs.invalid-capacity", "Capacity cannot be below the minimum orders");
        }
        dropMode = dropMode == null ? DropMode.DOORSTEP : dropMode;
        if (dropMode == DropMode.PICKUP_POINT && pickupPoint == null) {
            throw new RuleViolation("delivery-runs.pickup-point-required", "Pickup-point drops need a pickup point");
        }
        cutoffPolicy = cutoffPolicy == null ? CutoffPolicy.EXTEND : cutoffPolicy;
        if (extendDays < 1 || extendDays > 30) {
            throw new RuleViolation("delivery-runs.invalid-extension", "Extend days must be between 1 and 30");
        }
    }

    /** The same terms with a new cutoff, window and seller pickup (extensions). */
    public RunTerms reschedule(Instant newCutoff, DeliveryWindow newWindow, Instant newSellerPickupAt) {
        return new RunTerms(newWindow, newCutoff, opensAt != null && opensAt.isBefore(newCutoff) ? opensAt : null,
                newSellerPickupAt, feeMinor, currency, minOrders, capacity, dropMode, pickupPoint, cutoffPolicy,
                extendDays);
    }
}
