package com.afrotransact.inventory.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.domain.InboundDiscrepancy;
import com.afrotransact.inventory.domain.InboundLine;
import com.afrotransact.inventory.domain.InboundShipment;

/** Payloads of {@code inventory.inbound.*} (contracts/asyncapi/fragments/inventory.yaml). */
public final class InboundEventPayloads {

    private InboundEventPayloads() {
    }

    /** created / shipped / closed / cancelled: the shipment with its lines and discrepancies. */
    public record InboundShipmentEvent(UUID shipmentId, String number, String source, String externalRef,
                                       String ownerOrg, String nodeId, String status, String carrier,
                                       String trackingNumber, int expectedUnits, int receivedUnits,
                                       boolean discrepancy, List<LineState> lines,
                                       List<InboundDiscrepancy> discrepancies, Instant at) {

        static InboundShipmentEvent of(InboundShipment s) {
            List<InboundDiscrepancy> found = s.discrepancies();
            return new InboundShipmentEvent(s.id(), s.number(), s.source().name(), s.externalRef(), s.ownerOrg(),
                    s.nodeId(), s.status().name(), s.carrier(), s.trackingNumber(), s.expectedUnits(),
                    s.receivedUnits(), !found.isEmpty(), s.lines().stream().map(LineState::of).toList(), found,
                    s.updatedAt());
        }
    }

    /** One line's plan and receipt so far. */
    public record LineState(String sku, String atin, String unitLabel, int expectedQty, int receivedGood,
                            int receivedDamaged) {

        static LineState of(InboundLine l) {
            return new LineState(l.sku(), l.atin(), l.unitLabel(), l.expectedQty(), l.receivedGood(),
                    l.receivedDamaged());
        }
    }

    /** received: units of one receipt (offers switches the seller's listing to Fulfilled by AfroTransact). */
    public record InboundReceived(UUID shipmentId, String number, String source, String ownerOrg, String nodeId,
                                  String receiptRef, List<ReceivedLine> lines, Instant at) {
    }

    /** Units of one SKU in a receipt. */
    public record ReceivedLine(String sku, String atin, String unitLabel, int qtyGood, int qtyDamaged) {
    }
}
