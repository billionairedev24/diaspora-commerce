package com.afrotransact.retail.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A purchase order to one supplier in the supplier's currency with the FX rate captured at the PO date. All state
 * changes go through the methods below; persistence uses {@code version} for optimistic locking.
 */
public record PurchaseOrder(UUID id, String number, UUID supplierId, String supplierCode, String currency, FxRate fx,
                            String destinationNodeId, String incoterm, LocalDate orderDate, LocalDate expectedDate,
                            List<PoLine> lines, PoStatus status, ApprovalTier approvalTier, boolean shipmentBooked,
                            String createdBy, String approvedBy, List<PoHistoryEntry> history, Instant createdAt,
                            Instant updatedAt, long version) {

    private static final Set<PoStatus> CANCELLABLE = EnumSet.of(PoStatus.DRAFT, PoStatus.PENDING_APPROVAL,
            PoStatus.APPROVED, PoStatus.SENT, PoStatus.CONFIRMED);

    public PurchaseOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(supplierId, "supplierId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(fx, "fx");
        Objects.requireNonNull(orderDate, "orderDate");
        currency = Currencies.require(currency);
        if (!currency.equals(fx.currency())) {
            throw new RetailRuleViolation("retail.fx-invalid", "FX rate currency must match the PO currency");
        }
        if (destinationNodeId == null || destinationNodeId.isBlank()) {
            throw new RetailRuleViolation("retail.po-invalid", "Destination warehouse is required");
        }
        if (incoterm == null || !incoterm.matches("(EXW|FCA|FAS|FOB|CFR|CIF|CPT|CIP|DAP|DPU|DDP)( .{1,60})?")) {
            throw new RetailRuleViolation("retail.po-invalid", "Incoterm must be an Incoterms 2020 rule, e.g. FOB Tema");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
        history = history == null ? List.of() : List.copyOf(history);
        approvalTier = approvalTier == null ? ApprovalTier.STANDARD : approvalTier;
        Set<Integer> numbers = new HashSet<>();
        for (PoLine line : lines) {
            if (!numbers.add(line.lineNo())) {
                throw new IllegalArgumentException("Duplicate line number " + line.lineNo());
            }
        }
    }

    public static PurchaseOrder draft(UUID id, String number, Supplier supplier, FxRate fx, String nodeId,
                                      String incoterm, LocalDate orderDate, LocalDate expectedDate,
                                      List<PoLine> lines, ApprovalTier tier, String by, Instant now) {
        supplier.ensureCanReceivePurchaseOrders();
        requireLines(lines);
        return new PurchaseOrder(id, number, supplier.id(), supplier.code(), supplier.currency(), fx, nodeId,
                incoterm, orderDate, expectedDate, lines, PoStatus.DRAFT, tier, false, by, null,
                List.of(new PoHistoryEntry(now, PoStatus.DRAFT.name(), by, null)), now, now, 0);
    }

    /** Sum of line amounts in PO currency minor units. */
    public long totalMinor() {
        long total = 0;
        for (PoLine line : lines) {
            total = Math.addExact(total, line.amountMinor());
        }
        return total;
    }

    /** PO value in base-currency minor units at the captured FX rate. */
    public long totalBaseMinor() {
        return fx.toBase(totalMinor());
    }

    public PoLine line(int lineNo) {
        return lines.stream().filter(l -> l.lineNo() == lineNo).findFirst()
                .orElseThrow(() -> new RetailRuleViolation("retail.po-line-not-found",
                        "PO " + number + " has no line " + lineNo));
    }

    /** True once every line has been received in full (or the PO is closed). */
    public boolean receivingComplete() {
        return status == PoStatus.RECEIVED || status == PoStatus.CLOSED;
    }

    public void ensureVersion(Long expected) {
        if (expected != null && expected != version) {
            throw new RetailStateConflict("retail.stale-version", "PO " + number + " is at version " + version
                    + ", not " + expected + "; reload and retry");
        }
    }

    public PurchaseOrder replaceLines(List<PoLine> newLines, ApprovalTier tier, String by, Instant now) {
        require(EnumSet.of(PoStatus.DRAFT), "edit lines");
        requireLines(newLines);
        return copy(newLines, status, tier, shipmentBooked, approvedBy, entry(now, "Lines edited", by, null), now);
    }

    public PurchaseOrder submit(String by, Instant now) {
        require(EnumSet.of(PoStatus.DRAFT), "submit");
        return to(PoStatus.PENDING_APPROVAL, by, null, now);
    }

    public PurchaseOrder approve(String by, String note, Instant now) {
        require(EnumSet.of(PoStatus.PENDING_APPROVAL), "approve");
        return copy(lines, PoStatus.APPROVED, approvalTier, shipmentBooked, by,
                entry(now, PoStatus.APPROVED.name(), by, note), now);
    }

    public PurchaseOrder sendBack(String by, String note, Instant now) {
        require(EnumSet.of(PoStatus.PENDING_APPROVAL), "send back");
        if (note == null || note.isBlank()) {
            throw new RetailRuleViolation("retail.reason-required", "Say why the PO is sent back to the buyer");
        }
        return to(PoStatus.DRAFT, by, note, now);
    }

    public PurchaseOrder send(String by, Instant now) {
        require(EnumSet.of(PoStatus.APPROVED), "send to the supplier");
        return to(PoStatus.SENT, by, "PO PDF + EDI 850 sent", now);
    }

    public PurchaseOrder confirm(String by, String note, Instant now) {
        require(EnumSet.of(PoStatus.SENT), "record supplier confirmation");
        return to(PoStatus.CONFIRMED, by, note, now);
    }

    public PurchaseOrder bookShipment(String shipmentRef, String by, Instant now) {
        require(EnumSet.of(PoStatus.SENT, PoStatus.CONFIRMED, PoStatus.PARTIALLY_RECEIVED), "book a shipment");
        return copy(lines, status, approvalTier, true, approvedBy,
                entry(now, "Shipment " + shipmentRef + " booked", by, null), now);
    }

    public PurchaseOrder receive(List<ReceivedQty> received, String grn, String by, Instant now) {
        if (!status.receivable()) {
            throw new RetailStateConflict("retail.po-invalid-transition", "PO " + number + " is " + status
                    + " and cannot receive goods");
        }
        Map<Integer, int[]> byLine = new HashMap<>();
        for (ReceivedQty r : received) {
            line(r.poLineNo());
            int[] acc = byLine.computeIfAbsent(r.poLineNo(), k -> new int[2]);
            acc[0] += r.good();
            acc[1] += r.damaged();
        }
        List<PoLine> updated = new ArrayList<>();
        for (PoLine line : lines) {
            int[] acc = byLine.get(line.lineNo());
            updated.add(acc == null ? line : line.receive(acc[0], acc[1]));
        }
        boolean complete = updated.stream().allMatch(l -> l.qtyReceived() >= l.qtyOrdered());
        PoStatus next = complete ? PoStatus.RECEIVED : PoStatus.PARTIALLY_RECEIVED;
        return copy(updated, next, approvalTier, shipmentBooked, approvedBy,
                entry(now, next.name(), by, "Goods received (" + grn + ")"), now);
    }

    public PurchaseOrder recordInvoiced(Map<Integer, Integer> qtyByLine, String invoiceNumber, String by,
                                        Instant now) {
        List<PoLine> updated = lines.stream()
                .map(l -> qtyByLine.containsKey(l.lineNo()) ? l.invoice(qtyByLine.get(l.lineNo())) : l).toList();
        return copy(updated, status, approvalTier, shipmentBooked, approvedBy,
                entry(now, "Supplier invoice " + invoiceNumber + " matched", by, null), now);
    }

    /** Closing releases supplier payment: every received unit must be covered by a matched invoice. */
    public PurchaseOrder close(String by, Instant now) {
        require(EnumSet.of(PoStatus.RECEIVED, PoStatus.PARTIALLY_RECEIVED), "close");
        if (lines.stream().allMatch(l -> l.qtyReceived() == 0)) {
            throw new RetailRuleViolation("retail.po-not-received", "Nothing has been received on PO " + number);
        }
        List<Integer> unmatched = lines.stream().filter(l -> l.qtyInvoiced() < l.qtyReceived())
                .map(PoLine::lineNo).toList();
        if (!unmatched.isEmpty()) {
            throw new RetailRuleViolation("retail.po-not-matched", "Match the supplier invoice before closing; lines "
                    + unmatched + " have received units that are not invoiced");
        }
        return to(PoStatus.CLOSED, by, "Three-way matched — supplier payment released", now);
    }

    public PurchaseOrder cancel(String by, String reason, Instant now) {
        if (!CANCELLABLE.contains(status)) {
            throw new RetailStateConflict("retail.po-invalid-transition", "PO " + number + " is " + status
                    + " and can no longer be cancelled");
        }
        if (shipmentBooked) {
            throw new RetailStateConflict("retail.po-shipment-booked", "PO " + number
                    + " cannot be cancelled: a shipment is already booked");
        }
        if (reason == null || reason.isBlank()) {
            throw new RetailRuleViolation("retail.reason-required", "A cancellation reason is required");
        }
        return to(PoStatus.CANCELLED, by, reason, now);
    }

    private void require(Set<PoStatus> allowed, String action) {
        if (!allowed.contains(status)) {
            throw new RetailStateConflict("retail.po-invalid-transition", "Cannot " + action + " PO " + number
                    + " in status " + status);
        }
    }

    private PurchaseOrder to(PoStatus next, String by, String note, Instant now) {
        return copy(lines, next, approvalTier, shipmentBooked, approvedBy, entry(now, next.name(), by, note), now);
    }

    private List<PoHistoryEntry> entry(Instant now, String what, String by, String note) {
        List<PoHistoryEntry> h = new ArrayList<>(history);
        h.add(new PoHistoryEntry(now, what, by, note));
        return h;
    }

    private PurchaseOrder copy(List<PoLine> newLines, PoStatus newStatus, ApprovalTier tier, boolean booked,
                               String approver, List<PoHistoryEntry> newHistory, Instant now) {
        return new PurchaseOrder(id, number, supplierId, supplierCode, currency, fx, destinationNodeId, incoterm,
                orderDate, expectedDate, newLines, newStatus, tier, booked, createdBy, approver, newHistory,
                createdAt, now, version);
    }

    private static void requireLines(List<PoLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new RetailRuleViolation("retail.po-invalid", "A purchase order needs at least one line");
        }
        if (lines.size() > 200) {
            throw new RetailRuleViolation("retail.po-invalid", "At most 200 lines per purchase order");
        }
    }
}
