package com.afrotransact.retail.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A goods receipt (GRN) against an inbound shipment's ASN or directly against a PO. Receivers count batches while
 * OPEN; completion validates, costs and posts the lots to inventory (POSTING), then records discrepancies, landed
 * cost and the GL entry (COMPLETED).
 */
public record GoodsReceipt(UUID id, String number, UUID poId, String poNumber, UUID shipmentId, String shipmentRef,
                           String nodeId, String dock, ReceiptStatus status, List<ReceiptLine> lines,
                           List<LandedCharge> charges, String overApprovedBy, List<Discrepancy> discrepancies,
                           LandedCost landedCost, List<PostedLot> postedLots, UUID journalEntryId, String openedBy,
                           Instant openedAt, String completedBy, Instant completedAt, Instant updatedAt,
                           long version) {

    public GoodsReceipt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(poId, "poId");
        Objects.requireNonNull(status, "status");
        lines = lines == null ? List.of() : List.copyOf(lines);
        charges = charges == null ? List.of() : List.copyOf(charges);
        discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
        postedLots = postedLots == null ? List.of() : List.copyOf(postedLots);
        Set<Integer> numbers = new HashSet<>();
        for (ReceiptLine l : lines) {
            if (!numbers.add(l.lineNo())) {
                throw new RetailRuleViolation("retail.receipt-invalid", "Duplicate receipt line " + l.lineNo());
            }
        }
    }

    /** Opens a receipt at the dock for a shipment delivered to the FC; lines are pre-filled from the ASN. */
    public static GoodsReceipt forShipment(UUID id, String number, PurchaseOrder po, InboundShipment shipment,
                                           String dock, String by, Instant now) {
        if (shipment.stage() != ShipmentStage.DELIVERED_TO_FC) {
            throw new RetailStateConflict("retail.shipment-not-at-fc", "Shipment " + shipment.reference()
                    + " is " + shipment.stage() + "; receiving opens at Delivered to FC");
        }
        List<ReceiptLine> lines = new ArrayList<>();
        for (AsnLine a : shipment.lines()) {
            lines.add(new ReceiptLine(a.lineNo(), a.poLineNo(), a.lineNo(), a.itemId(), a.sku(), a.qty(), a.lotNo(),
                    null, null, null, 0, 0, null, QaResult.PENDING, false, false));
        }
        return new GoodsReceipt(id, number, po.id(), po.number(), shipment.id(), shipment.reference(),
                shipment.destinationNodeId(), dock, ReceiptStatus.OPEN, lines, List.of(), null, List.of(), null,
                List.of(), null, by, now, null, null, now, 0);
    }

    /** Opens a receipt directly against a PO (no ASN): one line per PO line with open quantity. */
    public static GoodsReceipt forPurchaseOrder(UUID id, String number, PurchaseOrder po, String dock, String by,
                                                Instant now) {
        if (!po.status().receivable()) {
            throw new RetailStateConflict("retail.po-not-receivable", "PO " + po.number() + " is " + po.status());
        }
        List<ReceiptLine> lines = new ArrayList<>();
        int n = 1;
        for (PoLine p : po.lines()) {
            if (p.openQty() > 0) {
                lines.add(new ReceiptLine(n++, p.lineNo(), null, p.itemId(), p.sku(), p.openQty(), null, null, null,
                        null, 0, 0, null, QaResult.PENDING, false, false));
            }
        }
        if (lines.isEmpty()) {
            throw new RetailStateConflict("retail.po-fully-received", "PO " + po.number() + " has nothing open");
        }
        return new GoodsReceipt(id, number, po.id(), po.number(), null, null, po.destinationNodeId(), dock,
                ReceiptStatus.OPEN, lines, List.of(), null, List.of(), null, List.of(), null, by, now, null, null,
                now, 0);
    }

    public boolean overReceiptApproved() {
        return overApprovedBy != null;
    }

    public void ensureVersion(Long expected) {
        if (expected != null && expected != version) {
            throw new RetailStateConflict("retail.stale-version", "Receipt " + number + " is at version " + version
                    + ", not " + expected + "; reload and retry");
        }
    }

    public ReceiptLine line(int lineNo) {
        return lines.stream().filter(l -> l.lineNo() == lineNo).findFirst()
                .orElseThrow(() -> new RetailRuleViolation("retail.receipt-line-not-found",
                        "Receipt " + number + " has no line " + lineNo));
    }

    /**
     * Records counts. Existing lines keep their expected quantity and ASN lot; new line numbers are split batches of
     * an existing PO line (expected 0).
     */
    public GoodsReceipt count(List<ReceiptLine> counted, Instant now) {
        requireOpen();
        List<ReceiptLine> out = new ArrayList<>();
        for (ReceiptLine c : counted) {
            ReceiptLine existing = lines.stream().filter(l -> l.lineNo() == c.lineNo()).findFirst().orElse(null);
            if (existing == null) {
                ReceiptLine parent = lines.stream().filter(l -> l.poLineNo() == c.poLineNo()).findFirst()
                        .orElseThrow(() -> new RetailRuleViolation("retail.receipt-invalid", "Split line "
                                + c.lineNo() + " must belong to a PO line already on the receipt"));
                out.add(new ReceiptLine(c.lineNo(), parent.poLineNo(), parent.asnLineNo(), parent.itemId(),
                        parent.sku(), 0, parent.asnLotNo(), c.lotNo(), c.mfgDate(), c.expDate(), c.qtyGood(),
                        c.qtyDamaged(), c.bin(), c.qa(), c.scanned(), true));
            } else {
                out.add(new ReceiptLine(existing.lineNo(), existing.poLineNo(), existing.asnLineNo(),
                        existing.itemId(), existing.sku(), existing.expectedQty(), existing.asnLotNo(), c.lotNo(),
                        c.mfgDate(), c.expDate(), c.qtyGood(), c.qtyDamaged(), c.bin(), c.qa(), c.scanned(),
                        existing.split()));
            }
        }
        for (ReceiptLine original : lines) {
            if (!original.split() && out.stream().noneMatch(l -> l.lineNo() == original.lineNo())) {
                out.add(original);
            }
        }
        out.sort((a, b) -> Integer.compare(a.lineNo(), b.lineNo()));
        return with(ReceiptStatus.OPEN, out, charges, overApprovedBy, discrepancies, landedCost, postedLots,
                journalEntryId, completedBy, completedAt, now);
    }

    public GoodsReceipt updateCharges(List<LandedCharge> newCharges, Instant now) {
        requireOpen();
        if (shipmentId != null) {
            throw new RetailStateConflict("retail.charges-on-shipment", "Receipt " + number
                    + " takes its landed-cost charges from shipment " + shipmentRef);
        }
        return with(status, lines, newCharges, overApprovedBy, discrepancies, landedCost, postedLots, journalEntryId,
                completedBy, completedAt, now);
    }

    public GoodsReceipt approveOverReceipt(String by, Instant now) {
        requireOpen();
        return with(status, lines, charges, by, discrepancies, landedCost, postedLots, journalEntryId, completedBy,
                completedAt, now);
    }

    /** Validated and costed: lots are being posted to inventory. */
    public GoodsReceipt startPosting(LandedCost cost, List<Discrepancy> found, Instant now) {
        requireOpen();
        return with(ReceiptStatus.POSTING, lines, charges, overApprovedBy, found, cost, postedLots, journalEntryId,
                completedBy, completedAt, now);
    }

    /** Inventory rejected the lots: back to counting. */
    public GoodsReceipt reopen(Instant now) {
        if (status != ReceiptStatus.POSTING) {
            throw new RetailStateConflict("retail.receipt-invalid-transition", "Receipt " + number + " is " + status);
        }
        return with(ReceiptStatus.OPEN, lines, charges, overApprovedBy, List.of(), null, List.of(), null, null, null,
                now);
    }

    public GoodsReceipt complete(List<PostedLot> lots, UUID journalId, String by, Instant now) {
        if (status != ReceiptStatus.POSTING) {
            throw new RetailStateConflict("retail.receipt-invalid-transition", "Receipt " + number + " is " + status);
        }
        return with(ReceiptStatus.COMPLETED, lines, charges, overApprovedBy, discrepancies, landedCost, lots,
                journalId, by, now, now);
    }

    public GoodsReceipt resolveClaim(String claimRef, String resolution, Instant now) {
        if (status != ReceiptStatus.COMPLETED) {
            throw new RetailStateConflict("retail.receipt-not-completed", "Receipt " + number + " is " + status);
        }
        if (resolution == null || resolution.isBlank()) {
            throw new RetailRuleViolation("retail.reason-required", "Describe how the claim was settled");
        }
        boolean found = false;
        List<Discrepancy> out = new ArrayList<>();
        for (Discrepancy d : discrepancies) {
            if (d.claimRef().equals(claimRef)) {
                out.add(d.resolve(resolution));
                found = true;
            } else {
                out.add(d);
            }
        }
        if (!found) {
            throw new RetailRuleViolation("retail.claim-not-found", "No claim " + claimRef + " on " + number);
        }
        return with(status, lines, charges, overApprovedBy, out, landedCost, postedLots, journalEntryId, completedBy,
                completedAt, now);
    }

    public long openClaims() {
        return discrepancies.stream().filter(d -> d.status() == ClaimStatus.OPEN).count();
    }

    private void requireOpen() {
        if (status != ReceiptStatus.OPEN) {
            throw new RetailStateConflict("retail.receipt-not-open", "Receipt " + number + " is " + status);
        }
    }

    private GoodsReceipt with(ReceiptStatus s, List<ReceiptLine> ls, List<LandedCharge> cs, String overBy,
                              List<Discrepancy> ds, LandedCost lc, List<PostedLot> lots, UUID journal,
                              String doneBy, Instant doneAt, Instant now) {
        return new GoodsReceipt(id, number, poId, poNumber, shipmentId, shipmentRef, nodeId, dock, s, ls, cs, overBy,
                ds, lc, lots, journal, openedBy, openedAt, doneBy, doneAt, now, version);
    }
}
