package com.afrotransact.deliveryruns.application;

import java.time.Instant;

/**
 * Staff extension. Only {@code cutoffAt} is required: without a new window, the window (and seller pickup) move by
 * as much as the cutoff does.
 */
public record ExtendCommand(Instant cutoffAt, Instant windowStart, Instant windowEnd, Instant sellerPickupAt,
                            String reason) {
}
