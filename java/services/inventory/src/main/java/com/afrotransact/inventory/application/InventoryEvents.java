package com.afrotransact.inventory.application;

/** Event types published by inventory-service (contracts/asyncapi/fragments/inventory.yaml). */
public final class InventoryEvents {

    /** Stock and lot events are keyed by SKU so every consumer sees one SKU's changes in order. */
    public static final String AGGREGATE_STOCK = "inventory";
    /** Reservation events are keyed by order id. */
    public static final String AGGREGATE_RESERVATION = "inventory-reservation";
    public static final String AGGREGATE_RECONCILIATION = "inventory-reconciliation";
    /** Inbound shipment events (topic at.inventory-inbound.events) are keyed by shipment id. */
    public static final String AGGREGATE_INBOUND = "inventory-inbound";

    public static final String AVAILABILITY_CHANGED = "inventory.availability.changed";
    public static final String LOT_CREATED = "inventory.lot.created";
    public static final String LOT_QUARANTINED = "inventory.lot.quarantined";
    public static final String LOT_RELEASED = "inventory.lot.released";
    public static final String LOT_EXPIRED = "inventory.lot.expired";
    public static final String LOT_WRITTEN_OFF = "inventory.lot.written_off";
    public static final String RESERVATION_HELD = "inventory.reservation.held";
    public static final String RESERVATION_COMMITTED = "inventory.reservation.committed";
    public static final String RESERVATION_RELEASED = "inventory.reservation.released";
    /** Some groups (one shipment's lines) shipped; the reservation may still hold other groups. */
    public static final String RESERVATION_GROUP_COMMITTED = "inventory.reservation.group_committed";
    /** Some groups (one cancelled shipment's lines) were given back; other groups stay held. */
    public static final String RESERVATION_GROUP_RELEASED = "inventory.reservation.group_released";
    public static final String RECONCILIATION_DRIFT = "inventory.reconciliation.drift_detected";
    /** S257 / S226: an FBA plan was created (WORKING). */
    public static final String INBOUND_CREATED = "inventory.inbound.created";
    /** Expected units are on their way (FBA plan shipped, retail ASN departed). */
    public static final String INBOUND_SHIPPED = "inventory.inbound.shipped";
    /** Units received at the FC against the shipment (one event per receipt). */
    public static final String INBOUND_RECEIVED = "inventory.inbound.received";
    /** Receiving finished; remaining expected units cancelled; discrepancies listed. */
    public static final String INBOUND_CLOSED = "inventory.inbound.closed";
    public static final String INBOUND_CANCELLED = "inventory.inbound.cancelled";

    private InventoryEvents() {
    }
}
