package com.afrotransact.retail.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.domain.Discrepancy;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.LandedCost;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierInvoice;

/** Event payloads (documented in contracts/asyncapi/fragments/retail.yaml). */
public final class RetailEventPayloads {

    private RetailEventPayloads() {
    }

    /** Item master change. */
    public record ItemEvent(UUID itemId, String sku, String gtin, String atin, String name, String listingStatus,
                            long priceMinor, String currency) {

        static ItemEvent of(RetailItem i) {
            return new ItemEvent(i.id(), i.sku(), i.gtin(), i.atin(), i.name(), i.listingStatus().name(),
                    i.priceMinor(), i.currency());
        }
    }

    /** Supplier change. */
    public record SupplierEvent(UUID supplierId, String code, String name, String status, String reason) {

        static SupplierEvent of(Supplier s) {
            return new SupplierEvent(s.id(), s.code(), s.name(), s.status().name(), s.statusReason());
        }
    }

    /** PO lifecycle change; totals in PO currency and in base currency at the PO-date rate. */
    public record PoEvent(UUID poId, String number, UUID supplierId, String supplierCode, String status,
                          String currency, long totalMinor, String baseCurrency, long totalBaseMinor,
                          BigDecimal fxRate, String approvalTier, String destinationNodeId, String actor,
                          String note) {

        static PoEvent of(PurchaseOrder po, String actor, String note) {
            return new PoEvent(po.id(), po.number(), po.supplierId(), po.supplierCode(), po.status().name(),
                    po.currency(), po.totalMinor(), po.fx().baseCurrency(), po.totalBaseMinor(),
                    po.fx().unitsPerBase(), po.approvalTier().name(), po.destinationNodeId(), actor, note);
        }
    }

    /** Shipment booking, ASN change or milestone. */
    public record ShipmentEvent(UUID shipmentId, String reference, UUID poId, String poNumber, String stage,
                                String mode, LocalDate eta, String nodeId, int units, String note) {

        static ShipmentEvent of(InboundShipment s) {
            return new ShipmentEvent(s.id(), s.reference(), s.poId(), s.poNumber(), s.stage().name(),
                    s.mode().name(), s.eta(), s.destinationNodeId(),
                    s.lines().stream().mapToInt(l -> l.qty()).sum(), s.note());
        }
    }

    /** One posted batch of a receipt. */
    public record ReceiptLot(String sku, String lotNo, int qty, String status, long unitLandedCostMinor) {
    }

    /** Goods receipt completed and its lots posted to inventory. */
    public record ReceiptPosted(UUID receiptId, String number, UUID poId, String poNumber, UUID shipmentId,
                                String nodeId, String currency, long landedTotalMinor, List<ReceiptLot> lots,
                                int discrepancies) {

        static ReceiptPosted of(GoodsReceipt r) {
            return new ReceiptPosted(r.id(), r.number(), r.poId(), r.poNumber(), r.shipmentId(), r.nodeId(),
                    r.landedCost().currency(), r.landedCost().totalMinor(),
                    r.postedLots().stream().map(l -> new ReceiptLot(l.sku(), l.lotNo(), l.qty(), l.status(),
                            l.unitLandedCostMinor())).toList(), r.discrepancies().size());
        }
    }

    /** Shortage, over-receipt or damage found at receiving. */
    public record DiscrepancyEvent(UUID receiptId, String receiptNumber, UUID poId, String poNumber, String claimRef,
                                   String type, String sku, int expectedQty, int receivedQty, int qty,
                                   String claimAgainst, String claimStatus, String note) {

        static DiscrepancyEvent of(GoodsReceipt r, Discrepancy d) {
            return new DiscrepancyEvent(r.id(), r.number(), r.poId(), r.poNumber(), d.claimRef(), d.type().name(),
                    d.sku(), d.expectedQty(), d.receivedQty(), d.qty(), d.claimAgainst(), d.status().name(),
                    d.note());
        }
    }

    /** Landed cost of one receipt line. */
    public record LandedLine(String sku, int qty, long fobMinor, long dutyMinor, long chargesMinor, long totalMinor,
                             long unitCostMinor) {
    }

    /** Landed cost allocated across a receipt's lines. */
    public record LandedCostAllocated(UUID receiptId, String receiptNumber, String shipmentRef, String currency,
                                      long fobMinor, long dutyMinor, long chargesMinor, long totalMinor,
                                      List<LandedLine> lines) {

        static LandedCostAllocated of(GoodsReceipt r) {
            LandedCost lc = r.landedCost();
            return new LandedCostAllocated(r.id(), r.number(), r.shipmentRef(), lc.currency(), lc.fobMinor(),
                    lc.dutyMinor(), lc.chargesMinor(), lc.totalMinor(), lc.lines().stream()
                    .map(l -> new LandedLine(l.sku(), l.qty(), l.fobValueMinor(), l.dutyMinor(),
                            l.totalMinor() - l.fobValueMinor() - l.dutyMinor(), l.totalMinor(), l.unitCostMinor()))
                    .toList());
        }
    }

    /** One journal line. */
    public record JournalLineView(String account, String accountName, long debitMinor, long creditMinor) {
    }

    /** A posted GL journal entry. */
    public record JournalPosted(UUID entryId, String number, LocalDate entryDate, String source, String sourceRef,
                                String memo, String currency, long amountMinor, List<JournalLineView> lines) {

        static JournalPosted of(JournalEntry e) {
            return new JournalPosted(e.id(), e.number(), e.entryDate(), e.source().name(), e.sourceRef(), e.memo(),
                    e.currency(), e.amountMinor(), e.lines().stream().map(l -> new JournalLineView(
                    l.account().code(), l.account().label(), l.debitMinor(), l.creditMinor())).toList());
        }
    }

    /** Three-way match outcome; exceptions name the buyer they are routed to. */
    public record InvoiceEvent(UUID invoiceId, String invoiceNumber, UUID poId, String poNumber, UUID supplierId,
                               String status, String outcome, String currency, long totalMinor, List<String> reasons,
                               String assignee) {

        static InvoiceEvent of(SupplierInvoice i, String assignee) {
            return new InvoiceEvent(i.id(), i.invoiceNumber(), i.poId(), i.poNumber(), i.supplierId(),
                    i.status().name(), i.match().outcome().name(), i.currency(), i.totalMinor(), i.match().reasons(),
                    assignee);
        }
    }

    /** One invoice whose payment is released. */
    public record PayableInvoice(UUID invoiceId, String invoiceNumber, String currency, long amountMinor) {
    }

    /** PO closed after the three-way match: payments-service pays the supplier. */
    public record PaymentReleased(UUID poId, String poNumber, UUID supplierId, String supplierCode, String currency,
                                  long amountMinor, List<PayableInvoice> invoices) {
    }
}
