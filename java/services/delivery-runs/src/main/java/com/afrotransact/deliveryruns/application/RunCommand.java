package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.PickupPoint;

/**
 * Create or reschedule a run. Null fee, currency, minimum, capacity, drop mode, pickup point, cutoff policy and extend
 * days take the location's defaults. A null fee is priced by the delivery fee rule for the location's distance.
 */
public record RunCommand(UUID locationId, Instant windowStart, Instant windowEnd, Instant cutoffAt, Instant opensAt,
                         Instant sellerPickupAt, Long feeMinor, String currency, Integer minOrders,
                         Integer capacityOrders, Integer capacityPackages, DropMode dropMode, PickupPoint pickupPoint,
                         CutoffPolicy cutoffPolicy, Integer extendDays, String notes) {
}
