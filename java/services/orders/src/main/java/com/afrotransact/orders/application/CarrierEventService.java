package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.UUID;

/** Applies carrier.tracking.updated events (carrier-gateway) to shipments. */
public interface CarrierEventService {

    /** Applies one scan once per event id; never moves a shipment backwards. */
    Result apply(UUID eventId, Scan scan);

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
