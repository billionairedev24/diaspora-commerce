package com.afrotransact.deliveryruns.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.RefundStatus;

/** The pre-order orders projection. */
public interface PreorderOrderRepository {

    /** @throws DuplicateEntryException when the order is already projected */
    PreorderOrder insert(PreorderOrder order);

    /** Optimistic: fails with {@link ConcurrentUpdateException} when {@code order.version()} is stale. */
    PreorderOrder update(PreorderOrder order);

    Optional<PreorderOrder> findById(String orderId);

    /** The projected orders among these order ids (unknown ids are absent), in no particular order. */
    List<PreorderOrder> findByIds(Collection<String> orderIds);

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

    /**
     * Records the refund progress orders-service reported for a cancelled order. A settled refund (REFUNDED / VOIDED)
     * is never moved back to PENDING or FAILED by a late event.
     *
     * @param amountMinor refunded / released amount (null keeps what is recorded)
     * @return whether a row was written (false for an unknown order or a stale event)
     */
    boolean recordRefund(String orderId, RefundStatus status, Long amountMinor, String error, Instant at);

    /** Orders of the window per refund status (orders without refund progress are absent). */
    Map<RefundStatus, Integer> refundCounts(UUID windowId);

    /** Retention: deletes the orders of windows delivered before {@code deliveredBefore}; returns rows deleted. */
    int deleteDeliveredBefore(LocalDate deliveredBefore);

    /**
     * @param orders       paid + late adds
     * @param revenueMinor their totals including delivery
     */
    record WindowTotals(int orders, long revenueMinor) {
    }
}
