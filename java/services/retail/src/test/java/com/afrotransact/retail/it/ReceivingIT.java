package com.afrotransact.retail.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.afrotransact.retail.application.LedgerService;
import com.afrotransact.retail.application.OpenReceiptCommand;
import com.afrotransact.retail.application.PoLineInput;
import com.afrotransact.retail.application.ReceiptLineInput;
import com.afrotransact.retail.application.ReceivingService;
import com.afrotransact.retail.domain.ChargeType;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.LandedCharge;
import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PostedLot;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.QaResult;
import com.afrotransact.retail.domain.ReceiptStatus;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.port.InventoryUnavailableException;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** S072 receiving against a PO (no ASN): inventory posting, retries, rejections, QA, over-receipt, lot uniqueness. */
class ReceivingIT extends RetailIntegrationTest {

    ReceivingService receiving;
    LedgerService ledger;

    @BeforeEach
    void wireServices(@Autowired ReceivingService receivingBean, @Autowired LedgerService ledgerBean) {
        this.receiving = receivingBean;
        this.ledger = ledgerBean;
    }

    private record Setup(Supplier supplier, RetailItem item, PurchaseOrder po) {
    }

    private Setup setup(int qty) {
        Supplier supplier = approvedSupplier("USD");
        RetailItem item = item(supplier, "Aged Basmati Rice, 10 lb", null, 820);
        PurchaseOrder po = sentPo(supplier, List.of(new PoLineInput(item.id(), qty, null)));
        return new Setup(supplier, item, po);
    }

    private GoodsReceipt counted(Setup s, String lot, int good, int damaged, QaResult qa) {
        as(TestActors.RECEIVER);
        GoodsReceipt r = receiving.open(new OpenReceiptCommand(null, s.po().id(), "Dock 2"));
        return receiving.count(r.id(), List.of(new ReceiptLineInput(1, 1, lot, TODAY.minusDays(30),
                TODAY.plusDays(700), good, damaged, "C-02-01", qa, true)), r.version());
    }

    @Test
    void receiptWithoutShipmentCreditsGrIrAndUsesItsOwnCharges() {
        Setup s = setup(1800);
        as(TestActors.RECEIVER);
        GoodsReceipt r = receiving.open(new OpenReceiptCommand(null, s.po().id(), "Dock 2"));
        as(TestActors.FINANCE);
        r = receiving.updateCharges(r.id(), List.of(new LandedCharge(ChargeType.FREIGHT, 295_000, "USD", null, null),
                new LandedCharge(ChargeType.RECEIVING, 30_000, "USD", null, null)), r.version());
        as(TestActors.RECEIVER);
        r = receiving.count(r.id(), List.of(new ReceiptLineInput(1, 1, "L2609-BAS-05", TODAY.minusDays(30),
                TODAY.plusDays(700), 1800, 0, "C-02-01", QaResult.PASS, true)), r.version());
        Stubs.receiptAccepted(DOWNSTREAM, r.number(),
                List.of(new Stubs.Lot(s.item().sku(), "L2609-BAS-05", 1800, "SELLABLE", 1012)));
        GoodsReceipt done = receiving.complete(r.id());

        assertThat(done.status()).isEqualTo(ReceiptStatus.COMPLETED);
        // FOB 1800 × $8.20 + 3.2% duty + $3,250 charges
        assertThat(done.landedCost().fobMinor()).isEqualTo(1_476_000);
        assertThat(done.landedCost().dutyMinor()).isEqualTo(47_232);
        assertThat(done.landedCost().totalMinor()).isEqualTo(1_476_000 + 47_232 + 325_000);
        assertThat(done.landedCost().lines().getFirst().unitCostMinor()).isEqualTo(1027);
        assertThat(done.discrepancies()).isEmpty();
        as(TestActors.FINANCE);
        JournalEntry je = ledger.entry(done.journalEntryId());
        assertThat(je.lines()).extracting(l -> l.account().code()).containsExactly("1300", "2110", "2120");
        assertThat(orders.get(s.po().id()).status()).isEqualTo(PoStatus.RECEIVED);
        DOWNSTREAM.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/receipts"))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].unitLandedCostMinor", WireMock.equalTo("1027")))
                .withRequestBody(WireMock.matchingJsonPath("$.receiptRef", WireMock.equalTo(r.number()))));
    }

    @Test
    void inventoryOutageLeavesTheReceiptPostingAndARetryCompletesItWithTheSameKey() {
        Setup s = setup(100);
        GoodsReceipt r = counted(s, "LOT-OUT-1", 100, 0, QaResult.PASS);
        Stubs.receiptAnswers(DOWNSTREAM, r.number(), 503, "{\"code\":\"upstream\"}");
        assertThatThrownBy(() -> receiving.complete(r.id())).isInstanceOf(InventoryUnavailableException.class);
        assertThat(receiving.get(r.id()).status()).isEqualTo(ReceiptStatus.POSTING);
        assertThatThrownBy(() -> receiving.count(r.id(), List.of(), null)).hasMessageContaining("POSTING");

        Stubs.receiptAccepted(DOWNSTREAM, r.number(),
                List.of(new Stubs.Lot(s.item().sku(), "LOT-OUT-1", 100, "SELLABLE", 846)));
        GoodsReceipt done = receiving.complete(r.id());
        assertThat(done.status()).isEqualTo(ReceiptStatus.COMPLETED);
        DOWNSTREAM.verify(2, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/receipts"))
                .withHeader("Idempotency-Key", WireMock.equalTo("retail-" + r.id())));
    }

    @Test
    void inventoryRejectionReopensTheReceiptAndReleasesItsLots() {
        Setup s = setup(100);
        GoodsReceipt r = counted(s, "LOT-REJ-1", 100, 0, QaResult.PASS);
        Stubs.receiptAnswers(DOWNSTREAM, r.number(), 422, "{\"code\":\"inventory.lot-rejected\",\"detail\":"
                + "\"Receipt rejected: expired lots or impossible dates\",\"fieldErrors\":{\"lines[0].expDate\":"
                + "\"Expired\"}}");
        assertThatThrownBy(() -> receiving.complete(r.id())).isInstanceOf(RetailRuleViolation.class)
                .satisfies(e -> {
                    RetailRuleViolation v = (RetailRuleViolation) e;
                    assertThat(v.code()).isEqualTo("retail.inventory-rejected");
                    assertThat(v.fieldErrors()).containsKey("lines[0].expDate");
                });
        GoodsReceipt reopened = receiving.get(r.id());
        assertThat(reopened.status()).isEqualTo(ReceiptStatus.OPEN);
        assertThat(jdbc.sql("select count(*) from received_lot where receipt_id = :r").param("r", r.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void qaFailedBatchesAreQuarantinedInInventory() {
        Setup s = setup(50);
        GoodsReceipt r = counted(s, "LOT-QA-01", 50, 0, QaResult.FAIL);
        Stubs.Lot lot = new Stubs.Lot(s.item().sku(), "LOT-QA-01", 50, "SELLABLE", 846);
        Stubs.receiptAccepted(DOWNSTREAM, r.number(), List.of(lot));
        GoodsReceipt done = receiving.complete(r.id());
        PostedLot posted = done.postedLots().getFirst();
        assertThat(posted.status()).isEqualTo("QUARANTINE");
        DOWNSTREAM.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/lots/" + posted.lotId()
                + "/quarantine")).withHeader("Authorization", WireMock.equalTo("Bearer " + Stubs.TOKEN)));
        as(TestActors.FINANCE);
        JournalEntry je = ledger.entry(done.journalEntryId());
        assertThat(je.lines()).extracting(l -> l.account().code()).containsExactly("1320", "2110", "2120");
        as(TestActors.BUYER);
        assertThat(suppliers.get(s.supplier().id()).scorecard().unitsDefective()).isEqualTo(50);
    }

    @Test
    void overReceiptBeyondTwoPercentNeedsApproval() {
        Setup s = setup(100);
        GoodsReceipt r = counted(s, "LOT-OVR-1", 103, 0, QaResult.PASS);
        assertThatThrownBy(() -> receiving.complete(r.id())).isInstanceOf(RetailRuleViolation.class)
                .satisfies(e -> assertThat(((RetailRuleViolation) e).fieldErrors()).containsKey("poLine[1]"));
        assertThat(receiving.get(r.id()).status()).isEqualTo(ReceiptStatus.OPEN);
        as(TestActors.FINANCE);
        receiving.approveOverReceipt(r.id(), null);
        as(TestActors.RECEIVER);
        Stubs.receiptAccepted(DOWNSTREAM, r.number(),
                List.of(new Stubs.Lot(s.item().sku(), "LOT-OVR-1", 103, "SELLABLE", 846)));
        GoodsReceipt done = receiving.complete(r.id());
        assertThat(done.discrepancies()).singleElement().satisfies(d -> assertThat(d.qty()).isEqualTo(3));
        assertThat(orders.get(s.po().id()).status()).isEqualTo(PoStatus.RECEIVED);
    }

    @Test
    void lotNumbersAreUniquePerSkuAcrossReceipts() {
        Setup s = setup(100);
        GoodsReceipt first = counted(s, "LOT-DUP-1", 40, 0, QaResult.PASS);
        Stubs.receiptAccepted(DOWNSTREAM, first.number(),
                List.of(new Stubs.Lot(s.item().sku(), "LOT-DUP-1", 40, "SELLABLE", 846)));
        receiving.complete(first.id());
        assertThat(orders.get(s.po().id()).status()).isEqualTo(PoStatus.PARTIALLY_RECEIVED);

        GoodsReceipt second = counted(s, "LOT-DUP-1", 60, 0, QaResult.PASS);
        assertThat(second.lines().getFirst().expectedQty()).isEqualTo(60);
        assertThat(receiving.validate(second.id()).line(1).errors())
                .contains("Lot LOT-DUP-1 already exists for this SKU");
        assertThatThrownBy(() -> receiving.complete(second.id())).isInstanceOf(RetailRuleViolation.class);
    }
}
