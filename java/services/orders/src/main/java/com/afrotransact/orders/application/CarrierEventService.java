package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.UUID;

/** Applies carrier.tracking.updated and carrier.pickup.ready events (carrier-gateway) to shipments. */
public interface CarrierEventService {

    /** Applies one scan once per event id; never moves a shipment backwards. */
    Result apply(UUID eventId, Scan scan);

    /**
     * carrier.pickup.ready (S219): the pickup shipments of the order {@code orderRef} names (order id, order number or
     * shipment id) that are confirmed or packed move to ready (actor PICKUP_POINT, source carrier), once per event id.
     * The notice must be for the order's customer; a pickup point set on the shipment must match.
     *
     * @return APPLIED when at least one shipment moved, UNCHANGED when all were already ready or beyond, UNMATCHED
     *         when no pickup shipment matches, REFUSED when none was eligible
     */
    Result pickupReady(UUID eventId, PickupReady notice);

    /**
     * Normalised {@code carrier.pickup.ready} payload.
     *
     * @param orderRef    order id, order number or shipment id given when the order was announced at the location
     * @param customerRef the order's customer id
     */
    record PickupReady(String locationId, String locationName, String orderRef, String customerRef, Instant readyAt) {
    }

    /**
     * Normalised carrier scan.
     *
     * @param reference      order / package reference given when buying the label (our shipment id)
     * @param state          label_created | picked_up | in_transit | out_for_delivery | delivered | delivery_failed |
     *                       returned | exception
     * @param carrierEventId carrier's event id (idempotency per shipment)
     */
    record Scan(String carrier, String trackingNumber, String reference, String state, String carrierEventId,
                Instant occurredAt, String description, String location) {
    }

    /** What happened to the event. */
    enum Result {
        APPLIED,
        UNCHANGED,
        IGNORED,
        DUPLICATE,
        UNMATCHED,
        REFUSED
    }
}
