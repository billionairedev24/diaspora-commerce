package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Platform pre-order settings (single row).
 *
 * @param enabled    master switch: off hides every pre-order placement
 * @param fallback   what shoppers see where pre-order is off
 * @param placements placements switched on (all by default)
 * @param payment    fees, payment methods, cancellation, late-add and missed-delivery options
 * @param updatedAt  last change
 * @param version    optimistic lock
 */
public record PreorderSettings(boolean enabled, Fallback fallback, Set<Placement> placements, PaymentSettings payment,
                               Instant updatedAt, long version) {

    public static final PreorderSettings DEFAULT = new PreorderSettings(true, Fallback.SHIP,
            EnumSet.allOf(Placement.class), PaymentSettings.DEFAULT, null, 0);

    public PreorderSettings {
        fallback = fallback == null ? Fallback.SHIP : fallback;
        placements = placements == null ? Set.of() : Set.copyOf(placements);
        payment = payment == null ? PaymentSettings.DEFAULT : payment;
    }

    public boolean placementOn(Placement placement) {
        return placements.contains(placement);
    }

    public PreorderSettings change(boolean on, Fallback fb, Set<Placement> pl, PaymentSettings pay, Instant now) {
        return new PreorderSettings(on, fb, pl, pay, now, version);
    }
}
