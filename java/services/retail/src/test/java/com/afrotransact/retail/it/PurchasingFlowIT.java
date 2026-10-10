package com.afrotransact.retail.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.retail.application.AsnLineInput;
import com.afrotransact.retail.application.BookShipmentCommand;
import com.afrotransact.retail.application.InvoiceMatchService;
import com.afrotransact.retail.application.LedgerService;
import com.afrotransact.retail.application.OpenReceiptCommand;
import com.afrotransact.retail.application.PoLineInput;
import com.afrotransact.retail.application.ReceiptLineInput;
import com.afrotransact.retail.application.ReceivingService;
import com.afrotransact.retail.application.RegisterInvoiceCommand;
import com.afrotransact.retail.application.ShipmentService;
import com.afrotransact.retail.domain.AllocationBasis;
import com.afrotransact.retail.domain.ChargeType;
import com.afrotransact.retail.domain.ClaimStatus;
import com.afrotransact.retail.domain.Discrepancy;
import com.afrotransact.retail.domain.DiscrepancyType;
import com.afrotransact.retail.domain.DocumentStatus;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.InvoiceLine;
import com.afrotransact.retail.domain.InvoiceStatus;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalLine;
import com.afrotransact.retail.domain.JournalSource;
import com.afrotransact.retail.domain.LandedCharge;
import com.afrotransact.retail.domain.ListingStatus;
import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.QaResult;
import com.afrotransact.retail.domain.ReceiptStatus;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailStateConflict;
import com.afrotransact.retail.domain.ShipmentDocument;
import com.afrotransact.retail.domain.ShipmentStage;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierInvoice;
import com.afrotransact.retail.domain.TransportMode;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The design's nine-step workflow end to end: product → PO → approval → ASN → transit → receipt → GL → match. */
class PurchasingFlowIT extends RetailIntegrationTest {

    ShipmentService shipments;
    ReceivingService receiving;
    InvoiceMatchService invoices;
    LedgerService ledger;

    @BeforeEach
    void wireServices(@Autowired ShipmentService shipmentsBean, @Autowired ReceivingService receivingBean, @Autowired InvoiceMatchService invoicesBean, @Autowired LedgerService ledgerBean) {
        this.shipments = shipmentsBean;
        this.receiving = receivingBean;
        this.invoices = invoicesBean;
        this.ledger = ledgerBean;
    }

    @Test
    void productToPaidPurchaseOrder() {
        Supplier supplier = approvedSupplier("GHS");

        // 1. Product master: catalog ATIN validated, SKU + GTIN issued, listing starts as Draft
        String atin = Stubs.platformAtin();
        RetailItem jollof = item(supplier, "AfroTransact Brand Jollof Seasoning, 6 oz", atin, 2005);
        RetailItem shito = item(supplier, "Shito Black Pepper Sauce, Hot, 12 oz", null, 3470);
        assertThat(jollof.atin()).isEqualTo(atin);
        assertThat(jollof.sku()).startsWith("AT-JOL-");
        assertThat(jollof.gtin()).startsWith("86001").hasSize(12);
        assertThat(jollof.listingStatus()).isEqualTo(ListingStatus.DRAFT);
        DOWNSTREAM.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/public/variants/" + atin)));
        as(TestActors.BUYER);
        assertThat(items.changeListing(jollof.id(), ListingStatus.LIVE, null).listingStatus())
                .isEqualTo(ListingStatus.LIVE);

        // 2–3. PO in GHS with FX captured, approved, sent, confirmed
        PurchaseOrder po = sentPo(supplier, List.of(new PoLineInput(jollof.id(), 2000, null),
                new PoLineInput(shito.id(), 1800, null)));
        assertThat(po.currency()).isEqualTo("GHS");
        assertThat(po.totalBaseMinor()).isEqualTo(665_110);
        assertThat(po.lines().getFirst().unitCostMinor()).isEqualTo(2005);
        as(TestActors.BUYER);
        po = orders.confirm(po.id(), "cargo ready 12 Oct", po.version());
        assertThat(po.status()).isEqualTo(PoStatus.CONFIRMED);
        assertThat(orders.edi850(po.id())).contains("BEG*00*SA*" + po.number()).contains("PO1*1*2000*EA*20.05")
                .contains("CUR*BY*GHS*15.42");

        // 4. Book the shipment; the PO can no longer be cancelled
        InboundShipment s = shipments.book(new BookShipmentCommand(po.id(), TransportMode.OCEAN_FCL, "MSC",
                "MSCU 482913 7", "MEDUTM2610441", "Tema, Ghana", "Port of Houston", TODAY, TODAY.plusDays(30),
                null, List.of(charge(ChargeType.FREIGHT, 485_000, AllocationBasis.VALUE),
                charge(ChargeType.INSURANCE, 21_000, null), charge(ChargeType.BROKERAGE, 35_000, null),
                charge(ChargeType.DRAYAGE, 78_000, AllocationBasis.WEIGHT),
                charge(ChargeType.RECEIVING, 42_000, AllocationBasis.QUANTITY))));
        assertThat(s.reference()).startsWith("SHP-").contains("GH");
        assertThat(s.lines()).hasSize(2);
        PurchaseOrder booked = orders.get(po.id());
        assertThat(booked.shipmentBooked()).isTrue();
        assertThatThrownBy(() -> orders.cancel(booked.id(), "changed mind", null))
                .isInstanceOf(RetailStateConflict.class);

        // Supplier packing list: batches, made and expiry dates
        s = shipments.updateAsn(s.id(), List.of(
                new AsnLineInput(1, 2000, "L2608-JOL-03", TODAY.minusDays(50), TODAY.plusDays(500)),
                new AsnLineInput(2, 1800, "L2608-SHI-06", TODAY.minusDays(45), TODAY.plusDays(320))), List.of(),
                s.version());

        // 5. Transit: departure blocked until documents are present; departure posts 1310 / 2110
        s = shipments.advance(s.id(), null);
        s = shipments.advance(s.id(), null);
        s = shipments.advance(s.id(), null);
        InboundShipment exportCleared = s;
        assertThatThrownBy(() -> shipments.advance(exportCleared.id(), null)).hasMessageContaining("missing");
        for (ShipmentDocument d : s.documents()) {
            s = shipments.setDocument(s.id(), d.name(), DocumentStatus.OK, null, null, null);
        }
        s = shipments.advance(s.id(), null);
        assertThat(s.stage()).isEqualTo(ShipmentStage.DEPARTED);
        as(TestActors.FINANCE);
        JournalEntry inTransit = ledger.journal(JournalSource.SHIPMENT_DEPARTED, s.reference(), 0, 10).items()
                .getFirst();
        assertThat(inTransit.lines()).extracting(l -> l.account().code()).containsExactly("1310", "2110");
        assertThat(inTransit.amountMinor()).isEqualTo(665_110);
        as(TestActors.BUYER);
        s = shipments.advance(s.id(), null);
        s = shipments.advance(s.id(), null);
        s = shipments.advance(s.id(), null);
        assertThat(s.stage()).isEqualTo(ShipmentStage.DELIVERED_TO_FC);

        // 6–7. Receive at the dock: damaged units and a shortage
        as(TestActors.RECEIVER);
        GoodsReceipt r = receiving.open(new OpenReceiptCommand(s.id(), null, "Dock 3"));
        assertThat(r.lines()).extracting(l -> l.expectedQty()).containsExactly(2000, 1800);
        assertThat(r.lines()).extracting(l -> l.asnLotNo()).containsExactly("L2608-JOL-03", "L2608-SHI-06");
        r = receiving.count(r.id(), List.of(
                new ReceiptLineInput(1, 1, "L2608-JOL-03", TODAY.minusDays(50), TODAY.plusDays(500), 1990, 10,
                        "R-01-02", QaResult.PASS, true),
                new ReceiptLineInput(2, 2, "L2608-SHI-06", TODAY.minusDays(45), TODAY.plusDays(320), 1700, 0,
                        "R-02-01", QaResult.PASS, true)), r.version());
        assertThat(receiving.validate(r.id()).ok()).isTrue();

        // 8. Complete: lots posted to inventory with the service token and an Idempotency-Key
        Stubs.receiptAccepted(DOWNSTREAM, r.number(), List.of(
                new Stubs.Lot(jollof.sku(), "L2608-JOL-03", 1990, "SELLABLE", 330),
                new Stubs.Lot(jollof.sku(), "L2608-JOL-03-DMG", 10, "QUARANTINE", 330),
                new Stubs.Lot(shito.sku(), "L2608-SHI-06", 1700, "SELLABLE", 400)));
        GoodsReceipt done = receiving.complete(r.id());
        assertThat(done.status()).isEqualTo(ReceiptStatus.COMPLETED);
        DOWNSTREAM.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/receipts"))
                .withHeader("Idempotency-Key", WireMock.equalTo("retail-" + r.id()))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Stubs.TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.receiptRef", WireMock.equalTo(r.number())))
                .withRequestBody(WireMock.matchingJsonPath("$.nodeId", WireMock.equalTo("HOU-FC")))
                .withRequestBody(WireMock.matchingJsonPath("$.inboundRef", WireMock.equalTo(r.shipmentRef())))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].qtyDamaged", WireMock.equalTo("10")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].shelfLifeDays", WireMock.equalTo("548")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[1].currency", WireMock.equalTo("USD"))));
        assertThat(done.postedLots()).hasSize(3);
        assertThat(done.landedCost().fobMinor()).isEqualTo(done.landedCost().lines().stream()
                .mapToLong(l -> l.fobValueMinor()).sum());
        assertThat(done.landedCost().chargesMinor()).isEqualTo(661_000);
        assertThat(done.discrepancies()).extracting(Discrepancy::type)
                .containsExactlyInAnyOrder(DiscrepancyType.DAMAGED, DiscrepancyType.SHORT);
        assertThat(receiving.complete(r.id()).version()).isEqualTo(done.version());
        DOWNSTREAM.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/receipts"))
                .withRequestBody(WireMock.matchingJsonPath("$.receiptRef", WireMock.equalTo(r.number()))));

        // PO, shipment, supplier scorecard and GL follow the receipt
        PurchaseOrder received = orders.get(po.id());
        assertThat(received.status()).isEqualTo(PoStatus.PARTIALLY_RECEIVED);
        assertThat(received.line(1).qtyReceived()).isEqualTo(2000);
        assertThat(received.line(1).qtyDamaged()).isEqualTo(10);
        assertThat(received.line(2).qtyReceived()).isEqualTo(1700);
        assertThat(shipments.get(s.id()).stage()).isEqualTo(ShipmentStage.RECEIVED);
        Supplier scored = suppliers.get(supplier.id());
        assertThat(scored.scorecard().receipts()).isEqualTo(1);
        assertThat(scored.scorecard().onTimeReceipts()).isEqualTo(1);
        assertThat(scored.scorecard().unitsDefective()).isEqualTo(10);
        as(TestActors.FINANCE);
        JournalEntry grn = ledger.entry(done.journalEntryId());
        assertThat(grn.lines()).extracting(l -> l.account().code()).containsExactly("1300", "1320", "1310", "2120");
        assertThat(grn.lines().stream().mapToLong(JournalLine::debitMinor).sum())
                .isEqualTo(done.landedCost().totalMinor());
        assertThat(grn.lines().get(3).creditMinor())
                .isEqualTo(done.landedCost().dutyMinor() + done.landedCost().chargesMinor());
        // S226: shipment events carry the ASN lines; inventory turns them into expected inbound at departure.
        List<String> departedSkus = jdbc.sql("""
                        select l ->> 'sku' from platform_outbox o, jsonb_array_elements(o.payload -> 'lines') l
                        where o.type = 'retail.shipment.stage-changed' and o.payload ->> 'stage' = 'DEPARTED'
                          and o.aggregate_id = :id
                        """).param("id", r.shipmentId().toString()).query(String.class).list();
        assertThat(departedSkus).containsExactlyInAnyOrder(jollof.sku(), shito.sku());
        assertThat(outboxTypes(done.id())).contains("retail.receipt.posted", "retail.landed-cost.allocated",
                "retail.receipt.discrepancy-raised");
        assertThat(outboxTypes(po.id())).contains("retail.po.created", "retail.po.approved", "retail.po.sent",
                "retail.po.received");
        assertThat(outboxTypes(grn.id())).containsExactly("retail.gl.journal.posted");

        // 9. Three-way match: open claims hold payment until settled, then the PO closes and payment is released
        SupplierInvoice inv = invoices.register(new RegisterInvoiceCommand(po.id(), unique("INV-"), TODAY, "GHS",
                null, List.of(new InvoiceLine(1, 2000, 2005), new InvoiceLine(2, 1700, 3470))), unique("idem-"));
        assertThat(inv.status()).isEqualTo(InvoiceStatus.ON_HOLD);
        assertThat(inv.match().reasons()).anyMatch(x -> x.contains("open receiving claim"));
        as(TestActors.BUYER);
        for (Discrepancy d : done.discrepancies()) {
            if (d.status() == ClaimStatus.OPEN) {
                receiving.resolveClaim(done.id(), d.claimRef(), "Credit note agreed with " + d.claimAgainst());
            }
        }
        as(TestActors.FINANCE);
        inv = invoices.rematch(inv.id());
        assertThat(inv.status()).isEqualTo(InvoiceStatus.MATCHED);
        JournalEntry ap = ledger.entry(inv.journalEntryId());
        assertThat(ap.lines()).extracting(l -> l.account().code()).contains("2110", "2100");
        PurchaseOrder closed = orders.close(po.id(), null);
        assertThat(closed.status()).isEqualTo(PoStatus.CLOSED);
        assertThat(invoices.get(inv.id()).status()).isEqualTo(InvoiceStatus.PAYMENT_RELEASED);
        assertThat(outboxTypes(po.id())).contains("retail.po.closed", "retail.payment.released");
        Map<String, Object> released = jdbc.sql("select payload::text as p, occurred_at from platform_outbox "
                        + "where type = 'retail.payment.released' and aggregate_id = :id")
                .param("id", po.id().toString()).query().singleRow();
        assertThat((String) released.get("p")).contains("\"amountMinor\": " + (2000L * 2005 + 1700L * 3470));
        assertThat(released.get("occurred_at")).isNotNull();
        assertThat(Instant.now()).isAfter(closed.createdAt());
    }

    private static LandedCharge charge(ChargeType type, long amount, AllocationBasis basis) {
        return new LandedCharge(type, amount, "USD", basis, null);
    }
}
