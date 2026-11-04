package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RunProgress;

/**
 * Moves a run along from what really happens to its orders (S264): the seller sweep, the drive and every delivery are
 * orders-service shipment events (the driver app and fulfilment forward them; staff can also mark a package
 * delivered), so the run starts collecting, goes out for delivery and completes without anyone pressing a button, and
 * a delivered package is recorded with its time (orders-service / payments start the seller payout hold from the
 * same event). Every call is one transaction, de-duplicated on the event id, and harmless when repeated or late.
 */
public interface RunProgressService {

    String CONSUMER = "delivery-runs-progress";

    /**
     * {@code orders.shipment.status_changed} of a PREORDER shipment: {@code picked_up} (collected from the seller),
     * {@code out_for_delivery}, {@code delivery_failed} or {@code returned}; other statuses change nothing.
     */
    void onShipmentMoved(UUID eventId, String orderId, String shipmentId, String to, Instant at);

    /** {@code orders.shipment.delivered} of a PREORDER shipment: the order is delivered at {@code deliveredAt}. */
    void onShipmentDelivered(UUID eventId, String orderId, String shipmentId, Instant deliveredAt);

    /**
     * {@code orders.shipment.cancelled}: the order leaves the run it rides on (a run cancelled as a whole has
     * cancelled its slots already). The run completes if that was the last order it waited for.
     */
    void onShipmentCancelled(UUID eventId, String orderId, String shipmentId, Instant at);

    /** How far the run has got (staff: {@code delivery-runs:read}). */
    RunProgress progress(UUID runId);
}
