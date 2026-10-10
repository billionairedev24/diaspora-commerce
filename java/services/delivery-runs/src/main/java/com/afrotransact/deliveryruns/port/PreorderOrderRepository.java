package com.afrotransact.deliveryruns.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.PreorderOrder;

/** The pre-order orders projection. */
public interface PreorderOrderRepository {

    /** @throws DuplicateEntryException when the order is already projected */
    PreorderOrder insert(PreorderOrder order);

    /** Optimistic: fails with {@link ConcurrentUpdateException} when {@code order.version()} is stale. */
    PreorderOrder update(PreorderOrder order);

    Optional<PreorderOrder> findById(String orderId);

    /** The order holding this pre-order package. */
    Optional<PreorderOrder> findByShipment(String shipmentId);

    /** Orders of a window, newest first. */
    List<PreorderOrder> findByWindow(UUID windowId);

    /**
     * Totals per window of orders that count (paid and late adds).
     *
     * @return window id → totals (windows without orders are absent)
     */
    Map<UUID, WindowTotals> totals(Collection<UUID> windowIds);

    /** Retention: deletes the orders of windows delivered before {@code deliveredBefore}; returns rows deleted. */
    int deleteDeliveredBefore(LocalDate deliveredBefore);

    /**
     * @param orders       paid + late adds
     * @param revenueMinor their totals including delivery
     */
    record WindowTotals(int orders, long revenueMinor) {
    }
}
