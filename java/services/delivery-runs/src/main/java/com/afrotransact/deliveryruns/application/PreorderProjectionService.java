package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RefundStatus;

/**
 * Keeps the pre-order orders projection from orders-service events (consumer {@code delivery-runs-preorders},
 * de-duplicated on event id through the platform inbox). Each call is one transaction.
 */
public interface PreorderProjectionService {

    String CONSUMER = "delivery-runs-preorders";

    /** {@code orders.order.placed} with PREORDER packages: projects the order (or completes a late add). */
    void onOrderPlaced(UUID eventId, OrderPlaced event);

    /** {@code orders.shipment.cancelled}: the package is cancelled; the order when none is left. */
    void onShipmentCancelled(UUID eventId, String orderId, String shipmentId, Instant at);

    /** {@code orders.order.cancelled} of a placed order: the pre-order is cancelled. */
    void onOrderCancelled(UUID eventId, String orderId, Instant at);

    /**
     * {@code orders.preorder.refund_settled} (REFUNDED or VOIDED) and {@code orders.preorder.refund_failed} (FAILED):
     * the refund progress of a cancelled pre-order. A settled refund is never moved back by a late event.
     */
    void onRefund(UUID eventId, String orderId, RefundStatus status, Long amountMinor, String error, Instant at);
}
