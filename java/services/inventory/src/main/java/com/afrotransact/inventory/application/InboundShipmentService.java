package com.afrotransact.inventory.application;

import java.util.UUID;

import com.afrotransact.inventory.domain.InboundShipment;
import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.inventory.port.InboundShipmentRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.UnitLabelClient;

/**
 * Inbound shipments (extends the {@code POST /v1/inbound} ASN): seller FBA plans with boxes, AT-FN unit labels and
 * carton labels, tracking, FC receiving against the plan with discrepancies (S257), and AfroTransact Retail ASNs
 * from retail-service events (S226).
 */
public interface InboundShipmentService {

    /** Creates a plan (WORKING): SKUs and quantities, destination FC assigned, AT-FN codes issued by catalog. */
    InboundShipment create(InboundCommands.CreatePlan command);

    InboundShipment get(UUID id);

    Page<InboundShipment> list(InboundShipmentRepository.InboundQuery query);

    /** Replaces lines and boxes while the plan is WORKING. */
    InboundShipment updatePlan(UUID id, InboundCommands.CreatePlan command, Long expectedVersion);

    /** Unit labels (one per unit, AT-FN) or carton labels (LPN per box) as one PDF or ZPL document. */
    UnitLabelClient.RenderedLabels labels(UUID id, LabelKind kind, String format, Integer lineNo);

    /** Carrier and tracking number entered by the seller (tracked through carrier-gateway when it can). */
    TrackingResult track(UUID id, String carrier, String trackingNumber);

    /** Buys the inbound label from carrier-gateway (FBA), one parcel per box. */
    InboundShipment buyLabel(UUID id, InboundCommands.BuyLabel command);

    /** Handed to the carrier: expected units become inbound ("Coming soon") at the FC. */
    InboundShipment ship(UUID id, Long expectedVersion);

    /** FC receiving against the plan: lots for the seller, received quantities, discrepancies so far. */
    ReceiptOutcome receive(UUID id, InboundCommands.ReceivePlan command, String idempotencyKey);

    /** Receiving finished: units that did not arrive are no longer inbound and are reported SHORT. */
    InboundShipment close(UUID id);

    InboundShipment cancel(UUID id);

    /** Where the seller ships to (the FC's address). */
    PostalAddress shipTo(InboundShipment shipment);

    /** retail.shipment.stage-changed: departure opens the ASN as inbound, RECEIVED closes it (inbox-deduplicated). */
    void retailShipmentChanged(UUID eventId, InboundCommands.RetailShipmentNotice notice);

    /** Which labels to print. */
    enum LabelKind {
        UNIT,
        BOX
    }

    /** The shipment after tracking was set, and whether carrier-gateway now tracks the number. */
    record TrackingResult(InboundShipment shipment, boolean trackingRegistered) {
    }

    /** The shipment after a receipt and what the receipt created. */
    record ReceiptOutcome(InboundShipment shipment, ReceiptResult receipt) {
    }
}
