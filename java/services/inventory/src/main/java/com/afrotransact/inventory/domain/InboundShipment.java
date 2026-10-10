package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Units announced to arrive at a node and what was received against them (S257 FBA plans, S226 retail ASNs).
 *
 * @param number           FBA-IN-006600 (FBA plans) or the retail shipment reference
 * @param externalRef      retail-service shipment reference (RETAIL_ASN), null for FBA plans
 * @param ownerOrg         stock owner: the seller org (FBA) or AfroTransact Retail
 * @param nodeId           destination fulfilment centre
 * @param requestedNodeId  the FC the seller asked for, if any
 * @param assignmentReason why {@code nodeId} was chosen
 * @param trackingSource   SELLER (carrier and number entered by the seller) or CARRIER_GATEWAY (label bought here)
 * @param receiptCount     receipts posted against the shipment so far
 */
public record InboundShipment(UUID id, String number, InboundSource source, String externalRef, String ownerOrg,
                              String nodeId, String requestedNodeId, String assignmentReason, String name,
                              InboundStatus status, PostalAddress shipFrom, String carrier, String trackingNumber,
                              String trackingSource, String carrierLabelId, Long labelCostMinor, String labelCurrency,
                              String labelDocumentUrl, LocalDate expectedArrival, int receiptCount,
                              List<InboundLine> lines, List<InboundBox> boxes, String createdBy, Instant createdAt,
                              Instant shippedAt, Instant closedAt, Instant updatedAt, long version) {

    public static final String TRACKING_SELLER = "SELLER";
    public static final String TRACKING_CARRIER_GATEWAY = "CARRIER_GATEWAY";

    public InboundShipment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        lines = lines == null ? List.of() : List.copyOf(lines);
        boxes = boxes == null ? List.of() : List.copyOf(boxes);
    }

    public Optional<InboundLine> line(String sku) {
        return lines.stream().filter(l -> l.sku().equals(sku)).findFirst();
    }

    public Optional<InboundLine> lineByUnitLabel(String unitLabel) {
        return lines.stream().filter(l -> unitLabel.equals(l.unitLabel())).findFirst();
    }

    public int expectedUnits() {
        return lines.stream().mapToInt(InboundLine::expectedQty).sum();
    }

    public int receivedUnits() {
        return lines.stream().mapToInt(InboundLine::received).sum();
    }

    /** Differences between plan and receipt so far; SHORT only once the shipment is closed. */
    public List<InboundDiscrepancy> discrepancies() {
        List<InboundDiscrepancy> found = new ArrayList<>();
        for (InboundLine l : lines) {
            if (l.expectedQty() == 0 && l.received() > 0) {
                found.add(new InboundDiscrepancy(l.sku(), l.atin(), InboundDiscrepancy.Type.UNEXPECTED, 0,
                        l.received(), l.received()));
                continue;
            }
            if (l.receivedDamaged() > 0) {
                found.add(new InboundDiscrepancy(l.sku(), l.atin(), InboundDiscrepancy.Type.DAMAGED, l.expectedQty(),
                        l.received(), l.receivedDamaged()));
            }
            if (l.overQty() > 0) {
                found.add(new InboundDiscrepancy(l.sku(), l.atin(), InboundDiscrepancy.Type.OVER, l.expectedQty(),
                        l.received(), l.overQty()));
            }
            if (status == InboundStatus.CLOSED && l.shortQty() > 0) {
                found.add(new InboundDiscrepancy(l.sku(), l.atin(), InboundDiscrepancy.Type.SHORT, l.expectedQty(),
                        l.received(), l.shortQty()));
            }
        }
        return found;
    }

    /** Lines and boxes can change while the plan is being prepared. */
    public InboundShipment replacePlan(List<InboundLine> newLines, List<InboundBox> newBoxes, Instant now) {
        require(InboundStatus.WORKING, "change the plan");
        return copy(status, newLines, newBoxes == null ? boxes : newBoxes, carrier, trackingNumber, trackingSource,
                carrierLabelId, labelCostMinor, labelCurrency, labelDocumentUrl, shippedAt, closedAt, receiptCount,
                now);
    }

    /** Unit label codes issued by catalog. */
    public InboundShipment withLines(List<InboundLine> newLines, Instant now) {
        return copy(status, newLines, boxes, carrier, trackingNumber, trackingSource, carrierLabelId,
                labelCostMinor, labelCurrency, labelDocumentUrl, shippedAt, closedAt, receiptCount, now);
    }

    /** Carrier and tracking number, entered by the seller or from a bought label. */
    public InboundShipment track(String newCarrier, String newTracking, String source, String labelId,
                                 Long costMinor, String currency, String documentUrl, Instant now) {
        if (!status.open() || status == InboundStatus.RECEIVING) {
            throw new InventoryStateConflict("inventory.inbound-invalid-state", "Shipment " + number + " is "
                    + status + "; tracking can no longer change");
        }
        return copy(status, lines, boxes, newCarrier, newTracking, source, labelId, costMinor, currency, documentUrl,
                shippedAt, closedAt, receiptCount, now);
    }

    /** The seller handed the boxes to the carrier: expected units become inbound at the FC. */
    public InboundShipment ship(Instant now) {
        require(InboundStatus.WORKING, "mark it shipped");
        if (lines.isEmpty()) {
            throw new InventoryRuleViolation("inventory.inbound-empty", "Add at least one SKU before shipping");
        }
        List<InboundLine> opened = lines.stream().map(l -> l.withInboundOpen(l.expectedQty())).toList();
        return copy(InboundStatus.SHIPPED, opened, boxes, carrier, trackingNumber, trackingSource, carrierLabelId,
                labelCostMinor, labelCurrency, labelDocumentUrl, now, closedAt, receiptCount, now);
    }

    /**
     * Records units received at the dock. Unknown SKUs are added as UNEXPECTED lines.
     *
     * @return the shipment and, per SKU, the expected units now consumed from inbound
     */
    public InboundShipment receive(String sku, String atin, int good, int damaged, int consumed, Instant now) {
        if (status != InboundStatus.SHIPPED && status != InboundStatus.RECEIVING) {
            throw new InventoryStateConflict("inventory.inbound-invalid-state", "Shipment " + number + " is "
                    + status + "; only shipped shipments can be received");
        }
        List<InboundLine> next = new ArrayList<>();
        boolean found = false;
        for (InboundLine l : lines) {
            if (l.sku().equals(sku)) {
                next.add(l.receive(good, damaged, consumed));
                found = true;
            } else {
                next.add(l);
            }
        }
        if (!found) {
            next.add(new InboundLine(lines.size() + 1, sku, atin, null, 0, good, damaged, 0));
        }
        return copy(InboundStatus.RECEIVING, next, boxes, carrier, trackingNumber, trackingSource, carrierLabelId,
                labelCostMinor, labelCurrency, labelDocumentUrl, shippedAt, closedAt, receiptCount, now);
    }

    public InboundShipment countReceipt(Instant now) {
        return copy(status, lines, boxes, carrier, trackingNumber, trackingSource, carrierLabelId, labelCostMinor,
                labelCurrency, labelDocumentUrl, shippedAt, closedAt, receiptCount + 1, now);
    }

    public InboundShipment boxesReceived(List<String> lpns, Instant now) {
        List<InboundBox> next = boxes.stream()
                .map(b -> lpns.contains(b.lpn()) && b.receivedAt() == null ? b.received(now) : b).toList();
        return copy(status, lines, next, carrier, trackingNumber, trackingSource, carrierLabelId, labelCostMinor,
                labelCurrency, labelDocumentUrl, shippedAt, closedAt, receiptCount, now);
    }

    /** Ends receiving: expected units that did not arrive stop counting as inbound and are reported SHORT. */
    public InboundShipment close(Instant now) {
        if (status != InboundStatus.SHIPPED && status != InboundStatus.RECEIVING) {
            throw new InventoryStateConflict("inventory.inbound-invalid-state", "Shipment " + number + " is "
                    + status + "; only shipped or receiving shipments can be closed");
        }
        List<InboundLine> settled = lines.stream().map(l -> l.withInboundOpen(0)).toList();
        return copy(InboundStatus.CLOSED, settled, boxes, carrier, trackingNumber, trackingSource, carrierLabelId,
                labelCostMinor, labelCurrency, labelDocumentUrl, shippedAt, now, receiptCount, now);
    }

    /** Cancels a plan or a shipment nothing was received against yet. */
    public InboundShipment cancel(Instant now) {
        if (status != InboundStatus.WORKING && status != InboundStatus.SHIPPED) {
            throw new InventoryStateConflict("inventory.inbound-invalid-state", "Shipment " + number + " is "
                    + status + " and can no longer be cancelled");
        }
        List<InboundLine> settled = lines.stream().map(l -> l.withInboundOpen(0)).toList();
        return copy(InboundStatus.CANCELLED, settled, boxes, carrier, trackingNumber, trackingSource,
                carrierLabelId, labelCostMinor, labelCurrency, labelDocumentUrl, shippedAt, now, receiptCount, now);
    }

    private void require(InboundStatus expected, String action) {
        if (status != expected) {
            throw new InventoryStateConflict("inventory.inbound-invalid-state", "Cannot " + action + ": shipment "
                    + number + " is " + status);
        }
    }

    private InboundShipment copy(InboundStatus s, List<InboundLine> l, List<InboundBox> b, String c, String t,
                                 String ts, String labelId, Long cost, String currency, String url, Instant shipped,
                                 Instant closed, int receipts, Instant now) {
        return new InboundShipment(id, number, source, externalRef, ownerOrg, nodeId, requestedNodeId,
                assignmentReason, name, s, shipFrom, c, t, ts, labelId, cost, currency, url, expectedArrival, receipts,
                l, b, createdBy, createdAt, shipped, closed, now, version);
    }
}
