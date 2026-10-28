package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * How far a run has got (S264), counted over the orders that still ride on it (cancelled and released slots are not
 * counted). Every figure is derived from the slots' stages, which come from orders-service shipment events.
 *
 * @param orders           orders on the run
 * @param pending          not collected from the seller yet
 * @param collected        collected, not on the road yet
 * @param outForDelivery   on the van
 * @param failed           a delivery attempt failed (can be retried)
 * @param delivered        handed over
 * @param returned         back at the depot
 * @param lastDeliveredAt  when the latest order was handed over
 */
public record RunProgress(int orders, int pending, int collected, int outForDelivery, int failed, int delivered,
                          int returned, Instant lastDeliveredAt) {

    /** Counts the confirmed slots of a run. */
    public static RunProgress of(Collection<RunOrder> slots) {
        Map<RunOrderStage, Integer> by = new EnumMap<>(RunOrderStage.class);
        Instant last = null;
        int n = 0;
        for (RunOrder s : slots) {
            if (s.status() != RunOrderStatus.CONFIRMED) {
                continue;
            }
            n++;
            by.merge(s.stage(), 1, Integer::sum);
            if (s.deliveredAt() != null && (last == null || s.deliveredAt().isAfter(last))) {
                last = s.deliveredAt();
            }
        }
        return new RunProgress(n, by.getOrDefault(RunOrderStage.PENDING, 0), by.getOrDefault(RunOrderStage.COLLECTED, 0),
                by.getOrDefault(RunOrderStage.OUT_FOR_DELIVERY, 0), by.getOrDefault(RunOrderStage.FAILED, 0),
                by.getOrDefault(RunOrderStage.DELIVERED, 0), by.getOrDefault(RunOrderStage.RETURNED, 0), last);
    }

    /** The seller sweep reached every order. */
    public boolean allCollected() {
        return orders > 0 && pending == 0;
    }

    /** Some order was collected: the run is no longer waiting for its first pickup. */
    public boolean anyCollected() {
        return orders > pending;
    }

    /** Every order is delivered or back: nothing is left to do on the run. */
    public boolean allClosed() {
        return orders > 0 && delivered + returned == orders;
    }
}
