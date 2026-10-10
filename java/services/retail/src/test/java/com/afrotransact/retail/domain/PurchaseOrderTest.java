package com.afrotransact.retail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PurchaseOrderTest {

    private final Supplier supplier = Fixtures.approvedAccra();
    private final UUID jollof = UUID.randomUUID();
    private final UUID shito = UUID.randomUUID();

    private PurchaseOrder draft() {
        return Fixtures.po(supplier, List.of(PoLine.of(1, jollof, "AT-JOL-6", "Jollof", "ACC-J", 2000, 2005),
                PoLine.of(2, shito, "AT-SHI-12", "Shito", "ACC-S", 1800, 3470)));
    }

    @Test
    void walksTheFullLifecycleAndKeepsHistory() {
        PurchaseOrder po = draft();
        assertThat(po.status()).isEqualTo(PoStatus.DRAFT);
        po = po.submit("ama", Fixtures.NOW);
        assertThat(po.status()).isEqualTo(PoStatus.PENDING_APPROVAL);
        po = po.approve("kwesi", "budget ok", Fixtures.NOW);
        assertThat(po.status()).isEqualTo(PoStatus.APPROVED);
        assertThat(po.approvedBy()).isEqualTo("kwesi");
        po = po.send("ama", Fixtures.NOW).confirm("ama", "cargo ready 12 Oct", Fixtures.NOW)
                .bookShipment("SHP-2610-GH01", "ama", Fixtures.NOW);
        assertThat(po.status()).isEqualTo(PoStatus.CONFIRMED);
        assertThat(po.shipmentBooked()).isTrue();

        po = po.receive(List.of(new ReceivedQty(1, 1990, 10)), "GRN-1", "dan", Fixtures.NOW);
        assertThat(po.status()).isEqualTo(PoStatus.PARTIALLY_RECEIVED);
        assertThat(po.line(1).qtyReceived()).isEqualTo(2000);
        assertThat(po.line(1).qtyDamaged()).isEqualTo(10);
        assertThat(po.line(2).openQty()).isEqualTo(1800);

        po = po.receive(List.of(new ReceivedQty(2, 1800, 0)), "GRN-2", "dan", Fixtures.NOW);
        assertThat(po.status()).isEqualTo(PoStatus.RECEIVED);
        assertThat(po.receivingComplete()).isTrue();

        po = po.recordInvoiced(Map.of(1, 2000, 2, 1800), "INV-1", "fin", Fixtures.NOW);
        po = po.close("fin", Fixtures.NOW);
        assertThat(po.status()).isEqualTo(PoStatus.CLOSED);
        assertThat(po.history()).extracting(PoHistoryEntry::status).containsSubsequence("DRAFT", "PENDING_APPROVAL",
                "APPROVED", "SENT", "CONFIRMED", "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED");
    }

    @Test
    void totalsAreInSupplierCurrencyAndBaseCurrencyAtTheCapturedRate() {
        PurchaseOrder po = draft();
        assertThat(po.totalMinor()).isEqualTo(2000L * 2005 + 1800L * 3470);
        // GHS 102,560.00 / 15.42 = USD 6,651.10
        assertThat(po.totalBaseMinor()).isEqualTo(665_110);
    }

    @Test
    void rejectsTransitionsOutOfOrder() {
        PurchaseOrder po = draft();
        assertThatThrownBy(() -> po.approve("x", null, Fixtures.NOW)).isInstanceOf(RetailStateConflict.class)
                .hasMessageContaining("DRAFT");
        assertThatThrownBy(() -> po.send("x", Fixtures.NOW)).isInstanceOf(RetailStateConflict.class);
        assertThatThrownBy(() -> po.close("x", Fixtures.NOW)).isInstanceOf(RetailStateConflict.class);
        assertThatThrownBy(() -> po.receive(List.of(new ReceivedQty(1, 1, 0)), "GRN", "x", Fixtures.NOW))
                .isInstanceOf(RetailStateConflict.class);
        PurchaseOrder submitted = po.submit("x", Fixtures.NOW);
        assertThatThrownBy(() -> submitted.submit("x", Fixtures.NOW)).isInstanceOf(RetailStateConflict.class);
        assertThatThrownBy(() -> submitted.replaceLines(po.lines(), ApprovalTier.STANDARD, "x", Fixtures.NOW))
                .isInstanceOf(RetailStateConflict.class);
    }

    @Test
    void sendBackReturnsToDraftAndNeedsANote() {
        PurchaseOrder submitted = draft().submit("ama", Fixtures.NOW);
        assertThatThrownBy(() -> submitted.sendBack("kwesi", " ", Fixtures.NOW))
                .isInstanceOf(RetailRuleViolation.class);
        PurchaseOrder back = submitted.sendBack("kwesi", "price above last PO", Fixtures.NOW);
        assertThat(back.status()).isEqualTo(PoStatus.DRAFT);
        assertThat(back.history().getLast().note()).isEqualTo("price above last PO");
    }

    @Test
    void cancelIsAllowedUntilAShipmentIsBooked() {
        PurchaseOrder sent = Fixtures.sent(draft());
        assertThat(sent.cancel("ama", "supplier out of stock", Fixtures.NOW).status()).isEqualTo(PoStatus.CANCELLED);
        assertThatThrownBy(() -> sent.cancel("ama", "", Fixtures.NOW)).isInstanceOf(RetailRuleViolation.class);
        PurchaseOrder booked = sent.bookShipment("SHP-1", "ama", Fixtures.NOW);
        assertThatThrownBy(() -> booked.cancel("ama", "too late", Fixtures.NOW))
                .isInstanceOf(RetailStateConflict.class).extracting("code").isEqualTo("retail.po-shipment-booked");
        PurchaseOrder received = booked.receive(List.of(new ReceivedQty(1, 2000, 0), new ReceivedQty(2, 1800, 0)),
                "GRN", "x", Fixtures.NOW);
        assertThatThrownBy(() -> received.cancel("ama", "late", Fixtures.NOW)).isInstanceOf(RetailStateConflict.class);
    }

    @Test
    void closingRequiresEveryReceivedUnitToBeInvoiced() {
        PurchaseOrder received = Fixtures.sent(draft())
                .receive(List.of(new ReceivedQty(1, 2000, 0), new ReceivedQty(2, 1800, 0)), "GRN", "x", Fixtures.NOW);
        assertThatThrownBy(() -> received.close("fin", Fixtures.NOW)).isInstanceOf(RetailRuleViolation.class)
                .extracting("code").isEqualTo("retail.po-not-matched");
        PurchaseOrder partlyInvoiced = received.recordInvoiced(Map.of(1, 2000), "INV-1", "fin", Fixtures.NOW);
        assertThatThrownBy(() -> partlyInvoiced.close("fin", Fixtures.NOW)).hasMessageContaining("[2]");
    }

    @Test
    void shortClosedPurchaseOrderCanBeClosedOnceReceivedUnitsAreInvoiced() {
        PurchaseOrder partial = Fixtures.sent(draft())
                .receive(List.of(new ReceivedQty(1, 1500, 0)), "GRN", "x", Fixtures.NOW)
                .recordInvoiced(Map.of(1, 1500), "INV-1", "fin", Fixtures.NOW);
        assertThat(partial.status()).isEqualTo(PoStatus.PARTIALLY_RECEIVED);
        assertThat(partial.close("fin", Fixtures.NOW).status()).isEqualTo(PoStatus.CLOSED);
    }

    @Test
    void inactiveOrOnboardingSuppliersCannotReceiveNewPurchaseOrders() {
        Supplier inactive = Fixtures.accra(SupplierStatus.INACTIVE);
        List<PoLine> lines = List.of(PoLine.of(1, jollof, "AT-JOL-6", "Jollof", "ACC-J", 10, 2005));
        assertThatThrownBy(() -> Fixtures.po(inactive, lines)).isInstanceOf(RetailRuleViolation.class)
                .extracting("code").isEqualTo("retail.supplier-inactive");
        assertThatThrownBy(() -> Fixtures.po(Fixtures.accra(SupplierStatus.ONBOARDING), lines))
                .isInstanceOf(RetailRuleViolation.class);
        assertThat(Fixtures.po(Fixtures.accra(SupplierStatus.ON_WATCH), lines).status()).isEqualTo(PoStatus.DRAFT);
    }

    @Test
    void validatesLinesIncotermAndFx() {
        assertThatThrownBy(() -> Fixtures.po(supplier, List.of())).isInstanceOf(RetailRuleViolation.class);
        assertThatThrownBy(() -> PoLine.of(1, jollof, "AT-JOL-6", "J", "S", 0, 100))
                .isInstanceOf(RetailRuleViolation.class);
        assertThatThrownBy(() -> PoLine.of(1, jollof, "AT-JOL-6", "J", "S", 1, 0))
                .isInstanceOf(RetailRuleViolation.class);
        assertThatThrownBy(() -> PurchaseOrder.draft(UUID.randomUUID(), "PO-1", supplier,
                new FxRate("GHS", "USD", new BigDecimal("15.42"), Fixtures.TODAY), "HOU-FC", "Free on board",
                Fixtures.TODAY, null, draft().lines(), ApprovalTier.STANDARD, "x", Fixtures.NOW))
                .isInstanceOf(RetailRuleViolation.class).hasMessageContaining("Incoterm");
        assertThatThrownBy(() -> PurchaseOrder.draft(UUID.randomUUID(), "PO-1", supplier,
                new FxRate("NGN", "USD", new BigDecimal("1528"), Fixtures.TODAY), "HOU-FC", "FOB Tema",
                Fixtures.TODAY, null, draft().lines(), ApprovalTier.STANDARD, "x", Fixtures.NOW))
                .isInstanceOf(RetailRuleViolation.class);
    }

    @Test
    void staleVersionIsAConflict() {
        PurchaseOrder po = draft();
        po.ensureVersion(null);
        po.ensureVersion(0L);
        assertThatThrownBy(() -> po.ensureVersion(3L)).isInstanceOf(RetailStateConflict.class)
                .extracting("code").isEqualTo("retail.stale-version");
    }

    @Test
    void approvalTierFollowsTheFinanceThreshold() {
        ApprovalPolicy policy = new DefaultApprovalPolicy(1_000_000);
        assertThat(policy.tierFor(999_999)).isEqualTo(ApprovalTier.STANDARD);
        assertThat(policy.tierFor(1_000_000)).isEqualTo(ApprovalTier.STANDARD);
        assertThat(policy.tierFor(1_000_001)).isEqualTo(ApprovalTier.FINANCE);
        assertThatThrownBy(() -> new DefaultApprovalPolicy(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
