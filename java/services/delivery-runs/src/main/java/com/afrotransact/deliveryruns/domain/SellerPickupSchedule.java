package com.afrotransact.deliveryruns.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * When the run's driver collects the packed pre-orders from the sellers (S264): normally the configured lead before
 * the delivery window opens ("the day before"), but never before the sellers had {@link #PACKING_TIME} after the window
 * closed to pack, and never later than {@link #BUFFER_BEFORE_WINDOW} before the first drop.
 */
public final class SellerPickupSchedule {

    /** Sellers pack for at least this long after the ordering window closes. */
    public static final Duration PACKING_TIME = Duration.ofHours(2);
    /** The van has everything this long before the delivery window opens. */
    public static final Duration BUFFER_BEFORE_WINDOW = Duration.ofHours(1);

    private SellerPickupSchedule() {
    }

    /**
     * @param cutoffAt    when ordering closed
     * @param windowStart when the delivery window opens
     * @param lead        wanted time before the window opens
     * @return a time between the cutoff and the window start (the cutoff itself when the two are too close)
     */
    public static Instant at(Instant cutoffAt, Instant windowStart, Duration lead) {
        Instant wanted = windowStart.minus(lead);
        Instant earliest = cutoffAt.plus(PACKING_TIME);
        Instant latest = windowStart.minus(BUFFER_BEFORE_WINDOW);
        if (earliest.isAfter(latest)) {
            return latest.isBefore(cutoffAt) ? cutoffAt : latest;
        }
        if (wanted.isBefore(earliest)) {
            return earliest;
        }
        return wanted.isAfter(latest) ? latest : wanted;
    }
}
