package com.afrotransact.deliveryruns.domain;

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
 * @param doorFee         effective door fee (null without a window or door delivery)
 * @param pickupFee       effective pickup fee (null without a window or pickup)
 * @param settings        platform settings (placements, payment)
 */
public record Resolution(AreaKey place, boolean on, PreorderStatus stage, PreorderWindow window,
                         Instant countdownTarget, Integer count, Fallback fallback, boolean metro, Money doorFee,
                         Money pickupFee, PreorderSettings settings) {
}
