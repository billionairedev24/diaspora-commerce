package com.afrotransact.deliveryruns.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * What the storefront shows for a shopper's delivery place.
 *
 * @param place           the place resolved
 * @param on              pre-order shown: a window found, the master switch on and the area on
 * @param stage           OPEN, UPCOMING or CLOSED (null when no window covers the place)
 * @param window          the window shown (null when none)
 * @param countdownTarget closes when open, opens when upcoming, the delivery day when closed
 * @param count           live pre-order count (only when the window shows it and it is not upcoming)
 * @param fallback        platform fallback where pre-order is off
 * @param metro           next-day metro city: pre-order is never shown
 * @param doorFee         door fee and the rule behind it (null without a window, door delivery, or when the address is
 *                        beyond how far AfroTransact delivers)
 * @param pickupFee       pickup fee and the rule behind it (null without a window or pickup)
 * @param beyondRange     a window covers the place but its address is beyond the last distance band and the window
 *                        offers no pickup: the order ships by carrier instead of going on a run
 * @param settings        platform settings (placements, payment)
 */
public record Resolution(AreaKey place, boolean on, PreorderStatus stage, PreorderWindow window,
                         Instant countdownTarget, Integer count, Fallback fallback, boolean metro, FeeSnapshot doorFee,
                         FeeSnapshot pickupFee, boolean beyondRange, PreorderSettings settings) {

    /**
     * The same window for a member with early access (S323): an UPCOMING window that opens within {@code hours} is
     * OPEN for them. Anything else is returned as it is.
     */
    public Resolution forEarlyAccess(Instant now, int hours) {
        if (hours <= 0 || window == null || stage != PreorderStatus.UPCOMING
                || window.details().opensAt().isAfter(now.plus(Duration.ofHours(hours)))) {
            return this;
        }
        return new Resolution(place, on, PreorderStatus.OPEN, window, window.details().closesAt(), count, fallback,
                metro, doorFee, pickupFee, beyondRange, settings);
    }
}
