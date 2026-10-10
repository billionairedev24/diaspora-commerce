package com.afrotransact.retail.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An inbound shipment booked against a PO, carrying the supplier's advance ship notice (packing list lines with
 * batch, made and expiry dates, plus cartons), customs documents, landed-cost charges and the nine-step timeline.
 * ASN lines are editable until departure; departure is blocked while documents are missing; customs clearance is
 * blocked by holds.
 */
public record InboundShipment(UUID id, String reference, UUID poId, String poNumber, UUID supplierId,
                              TransportMode mode, String carrier, String trackingRef, String billOfLading,
                              String originPort, String destinationPort, String destinationNodeId, LocalDate etd,
                              LocalDate eta, ShipmentStage stage, List<AsnLine> lines, List<Carton> cartons,
                              List<ShipmentDocument> documents, List<LandedCharge> charges,
                              List<Milestone> milestones, String note, Instant createdAt, Instant updatedAt,
                              long version) {

    public InboundShipment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(poId, "poId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(stage, "stage");
        if (etd != null && eta != null && eta.isBefore(etd)) {
            throw new RetailRuleViolation("retail.shipment-invalid", "ETA cannot be before ETD");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
        cartons = cartons == null ? List.of() : List.copyOf(cartons);
        documents = documents == null ? List.of() : List.copyOf(documents);
        charges = charges == null ? List.of() : List.copyOf(charges);
        milestones = milestones == null ? List.of() : List.copyOf(milestones);
    }

    /** Books a shipment for a PO: documents for the mode start as MISSING, ASN lines come from the PO. */
    public static InboundShipment book(UUID id, String reference, PurchaseOrder po, TransportMode mode, String carrier,
                                       String trackingRef, String billOfLading, String originPort,
                                       String destinationPort, LocalDate etd, LocalDate eta, List<AsnLine> lines,
                                       List<LandedCharge> charges, String by, Instant now) {
        if (lines == null || lines.isEmpty()) {
            throw new RetailRuleViolation("retail.asn-invalid", "A shipment needs at least one ASN line");
        }
        validateAgainstPo(po, lines);
        List<ShipmentDocument> docs = mode.requiredDocuments().stream()
                .map(n -> new ShipmentDocument(n, DocumentStatus.MISSING, null, null, now)).toList();
        return new InboundShipment(id, reference, po.id(), po.number(), po.supplierId(), mode, carrier, trackingRef,
                billOfLading, originPort, destinationPort, po.destinationNodeId(), etd, eta, ShipmentStage.BOOKED,
                lines, List.of(), docs, charges, List.of(new Milestone(ShipmentStage.BOOKED, now, by)),
                "ASN awaiting supplier batch details", now, now, 0);
    }

    public boolean asnLocked() {
        return stage.atOrAfter(ShipmentStage.DEPARTED);
    }

    public AsnLine line(int lineNo) {
        return lines.stream().filter(l -> l.lineNo() == lineNo).findFirst()
                .orElseThrow(() -> new RetailRuleViolation("retail.asn-line-not-found",
                        "Shipment " + reference + " has no ASN line " + lineNo));
    }

    public void ensureVersion(Long expected) {
        if (expected != null && expected != version) {
            throw new RetailStateConflict("retail.stale-version", "Shipment " + reference + " is at version "
                    + version + ", not " + expected + "; reload and retry");
        }
    }

    /** Replaces ASN lines and cartons (supplier packing list); locked once the cargo has departed. */
    public InboundShipment updateAsn(PurchaseOrder po, List<AsnLine> newLines, List<Carton> newCartons, Instant now) {
        if (asnLocked()) {
            throw new RetailStateConflict("retail.asn-locked", "ASN of " + reference + " is locked after departure");
        }
        if (newLines == null || newLines.isEmpty()) {
            throw new RetailRuleViolation("retail.asn-invalid", "A shipment needs at least one ASN line");
        }
        validateAgainstPo(po, newLines);
        List<Carton> cs = newCartons == null ? List.of() : newCartons;
        validateCartons(newLines, cs);
        return new InboundShipment(id, reference, poId, poNumber, supplierId, mode, carrier, trackingRef,
                billOfLading, originPort, destinationPort, destinationNodeId, etd, eta, stage, newLines, cs, documents,
                charges, milestones, note, createdAt, now, version);
    }

    public InboundShipment setDocument(String name, DocumentStatus status, String ref, String docNote, Instant now) {
        if (stage == ShipmentStage.RECEIVED) {
            throw new RetailStateConflict("retail.shipment-closed", "Shipment " + reference + " is already received");
        }
        List<ShipmentDocument> docs = new ArrayList<>(documents.stream().filter(d -> !d.name().equals(name)).toList());
        int index = indexOf(name);
        ShipmentDocument updated = new ShipmentDocument(name, status, ref, docNote, now);
        if (index < 0) {
            docs.add(updated);
        } else {
            docs.add(index, updated);
        }
        String newNote = status == DocumentStatus.HOLD ? name + " on hold" + (docNote == null ? "" : ": " + docNote)
                : note;
        if (status == DocumentStatus.OK && docs.stream().noneMatch(d -> d.status() == DocumentStatus.HOLD)
                && note != null && note.contains("hold")) {
            newNote = "Hold released";
        }
        return new InboundShipment(id, reference, poId, poNumber, supplierId, mode, carrier, trackingRef,
                billOfLading, originPort, destinationPort, destinationNodeId, etd, eta, stage, lines, cartons, docs,
                charges, milestones, newNote, createdAt, now, version);
    }

    public InboundShipment updateCharges(List<LandedCharge> newCharges, Instant now) {
        if (stage == ShipmentStage.RECEIVED) {
            throw new RetailStateConflict("retail.shipment-closed", "Shipment " + reference
                    + " is received; landed cost is final");
        }
        return new InboundShipment(id, reference, poId, poNumber, supplierId, mode, carrier, trackingRef,
                billOfLading, originPort, destinationPort, destinationNodeId, etd, eta, stage, lines, cartons,
                documents, newCharges, milestones, note, createdAt, now, version);
    }

    /**
     * Moves to the next milestone. Departure needs every document present (not MISSING); customs clearance needs every
     * document OK and no hold. "Received & put away" is reached only by completing the goods receipt.
     */
    public InboundShipment advance(String by, Instant now) {
        if (stage.atOrAfter(ShipmentStage.DELIVERED_TO_FC)) {
            throw new RetailStateConflict("retail.shipment-needs-receipt", "Shipment " + reference
                    + " is at the FC; complete the goods receipt to finish it");
        }
        ShipmentStage next = ShipmentStage.values()[stage.ordinal() + 1];
        if (next == ShipmentStage.DEPARTED) {
            List<String> missing = documents.stream().filter(d -> d.status() == DocumentStatus.MISSING)
                    .map(ShipmentDocument::name).toList();
            if (!missing.isEmpty()) {
                throw new RetailRuleViolation("retail.documents-missing", "Departure blocked: missing " + missing);
            }
        }
        if (next == ShipmentStage.CUSTOMS_CLEARED) {
            List<String> holds = documents.stream().filter(d -> d.status() == DocumentStatus.HOLD)
                    .map(ShipmentDocument::name).toList();
            if (!holds.isEmpty()) {
                throw new RetailRuleViolation("retail.customs-hold", "Customs clearance blocked by hold on " + holds);
            }
            List<String> open = documents.stream().filter(d -> d.status() != DocumentStatus.OK)
                    .map(ShipmentDocument::name).toList();
            if (!open.isEmpty()) {
                throw new RetailRuleViolation("retail.documents-pending", "Customs clearance needs " + open);
            }
        }
        return to(next, by, now);
    }

    public InboundShipment markReceived(String by, Instant now) {
        if (stage != ShipmentStage.DELIVERED_TO_FC) {
            throw new RetailStateConflict("retail.shipment-not-at-fc", "Shipment " + reference + " is " + stage);
        }
        return to(ShipmentStage.RECEIVED, by, now);
    }

    private InboundShipment to(ShipmentStage next, String by, Instant now) {
        List<Milestone> ms = new ArrayList<>(milestones);
        ms.add(new Milestone(next, now, by));
        return new InboundShipment(id, reference, poId, poNumber, supplierId, mode, carrier, trackingRef,
                billOfLading, originPort, destinationPort, destinationNodeId, etd, eta, next, lines, cartons,
                documents, charges, ms, note, createdAt, now, version);
    }

    private int indexOf(String name) {
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static void validateAgainstPo(PurchaseOrder po, List<AsnLine> lines) {
        Set<Integer> numbers = new HashSet<>();
        Map<Integer, Integer> byPoLine = new HashMap<>();
        for (AsnLine l : lines) {
            if (!numbers.add(l.lineNo())) {
                throw new RetailRuleViolation("retail.asn-invalid", "Duplicate ASN line " + l.lineNo());
            }
            PoLine pl = po.line(l.poLineNo());
            if (!pl.itemId().equals(l.itemId())) {
                throw new RetailRuleViolation("retail.asn-invalid", "ASN line " + l.lineNo()
                        + " does not match the item on PO line " + l.poLineNo());
            }
            byPoLine.merge(l.poLineNo(), l.qty(), Integer::sum);
        }
        byPoLine.forEach((poLine, qty) -> {
            int ordered = po.line(poLine).qtyOrdered();
            if (qty * 100L > ordered * 102L) {
                throw new RetailRuleViolation("retail.asn-over-shipped", "ASN ships " + qty + " units on PO line "
                        + poLine + " but only " + ordered + " were ordered (+2% max)");
            }
        });
    }

    private static void validateCartons(List<AsnLine> lines, List<Carton> cartons) {
        Set<String> ssccs = new HashSet<>();
        Map<Integer, Integer> units = new HashMap<>();
        for (Carton c : cartons) {
            if (!ssccs.add(c.sscc())) {
                throw new RetailRuleViolation("retail.asn-invalid", "Duplicate SSCC " + c.sscc());
            }
            if (lines.stream().noneMatch(l -> l.lineNo() == c.asnLineNo())) {
                throw new RetailRuleViolation("retail.asn-invalid", "Carton " + c.sscc() + " points to unknown ASN line "
                        + c.asnLineNo());
            }
            units.merge(c.asnLineNo(), c.qty(), Integer::sum);
        }
        for (AsnLine l : lines) {
            Integer packed = units.get(l.lineNo());
            if (packed != null && packed != l.qty()) {
                throw new RetailRuleViolation("retail.asn-carton-mismatch", "ASN line " + l.lineNo() + " ships "
                        + l.qty() + " units but its cartons hold " + packed);
            }
        }
    }
}
