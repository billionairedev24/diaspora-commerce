package com.afrotransact.deliveryruns.application;

import java.time.Instant;

import com.afrotransact.deliveryruns.domain.LocalAdvertorial;
import com.afrotransact.deliveryruns.port.AttributedOrders;

/**
 * An advertorial as the back office shows it.
 *
 * @param advertorial  the advertorial
 * @param sourceName   the window's name, or the run's town and delivery day (null when the source is gone)
 * @param sourceCode   the window code (null for runs)
 * @param sourceStatus the window or run status now
 * @param sourceCloses when ordering closes (window close, run cutoff)
 * @param locationName the location it serves
 * @param serving      visitors can see it right now
 * @param results      views, clicks and orders
 */
public record AdvertorialView(LocalAdvertorial advertorial, String sourceName, String sourceCode,
                              String sourceStatus, Instant sourceCloses, String locationName, boolean serving,
                              Results results) {

    /**
     * What an advertorial brought.
     *
     * @param views           times it was shown
     * @param clicks          times its button was clicked
     * @param ctrPct          clicks / views x 100 (null without views)
     * @param orders          orders that came through the advertorial and still stand; the same rule for a window and a
     *                        run (see {@link AttributedOrders}): a window's pre-orders that are not cancelled, a run's
     *                        orders that still hold a slot
     * @param cancelledOrders orders that came through it and were cancelled or refunded
     */
    public record Results(long views, long clicks, Double ctrPct, int orders, int cancelledOrders) {

        public static Results of(long views, long clicks, AttributedOrders sold) {
            Double ctr = views == 0 ? null : Math.round(clicks * 1000.0 / views) / 10.0;
            return new Results(views, clicks, ctr, sold.live(), sold.cancelled());
        }
    }
}
