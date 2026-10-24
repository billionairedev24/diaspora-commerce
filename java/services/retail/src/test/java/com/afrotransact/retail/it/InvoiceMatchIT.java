package com.afrotransact.retail.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.application.CogsPostingService;
import com.afrotransact.retail.application.InvoiceMatchService;
import com.afrotransact.retail.application.LedgerService;
import com.afrotransact.retail.application.OpenReceiptCommand;
import com.afrotransact.retail.application.PoLineInput;
import com.afrotransact.retail.application.ReceiptLineInput;
import com.afrotransact.retail.application.ReceivingService;
import com.afrotransact.retail.application.RegisterInvoiceCommand;
import com.afrotransact.retail.application.ShippedLine;
import com.afrotransact.retail.domain.GlAccount;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.InvoiceLine;
import com.afrotransact.retail.domain.InvoiceStatus;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalLine;
import com.afrotransact.retail.domain.MatchLineStatus;
import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.QaResult;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierInvoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** S074 three-way match outcomes, FX gain/loss, idempotency, and S073 COGS from inventory events. */
class InvoiceMatchIT extends RetailIntegrationTest {

    ReceivingService receiving;
    InvoiceMatchService invoices;
    LedgerService ledger;
    CogsPostingService cogs;

    @BeforeEach
    void wireServices(@Autowired ReceivingService receivingBean, @Autowired InvoiceMatchService invoicesBean, @Autowired LedgerService ledgerBean, @Autowired CogsPostingService cogsBean) {
        this.receiving = receivingBean;
        this.invoices = invoicesBean;
        this.ledger = ledgerBean;
        this.cogs = cogsBean;
    }

    private record Received(Supplier supplier, RetailItem item, PurchaseOrder po) {
    }

    /** GHS PO of 1000 units at GHS 20.00 (rate 15.42), fully received without discrepancies. */
    private Received received() {
        Supplier supplier = approvedSupplier("GHS");
        RetailItem item = item(supplier, "Jollof Seasoning, 6 oz", null, 2_000);
        PurchaseOrder po = sentPo(supplier, List.of(new PoLineInput(item.id(), 1000, null)));
        as(TestActors.RECEIVER);
        GoodsReceipt r = receiving.open(new OpenReceiptCommand(null, po.id(), "Dock 1"));
        String lot = unique("L");
        r = receiving.count(r.id(), List.of(new ReceiptLineInput(1, 1, lot, TODAY.minusDays(10),
                TODAY.plusDays(540), 1000, 0, "A-03-02", QaResult.PASS, true)), r.version());
        Stubs.receiptAccepted(DOWNSTREAM, r.number(), List.of(new Stubs.Lot(item.sku(), lot, 1000, "SELLABLE", 135)));
        receiving.complete(r.id());
        as(TestActors.FINANCE);
        return new Received(supplier, item, orders.get(po.id()));
    }

    @Test
    void priceVarianceRoutesToTheBuyerAndFinanceCanAcceptIt() {
        Received rc = received();
        SupplierInvoice inv = invoices.register(new RegisterInvoiceCommand(rc.po().id(), unique("INV-"), TODAY, "GHS",
                new BigDecimal("15.80"), List.of(new InvoiceLine(1, 1000, 2_100))), unique("k-"));
        assertThat(inv.status()).isEqualTo(InvoiceStatus.EXCEPTION);
        assertThat(inv.match().lines().getFirst().status()).isEqualTo(MatchLineStatus.PRICE_VARIANCE);
        String payload = jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id and type = "
                + "'retail.match.exception'").param("id", inv.id().toString()).query(String.class).single();
        assertThat(payload).contains("\"assignee\": \"ama.buyer\"").contains("5.00%");
        assertThatThrownBy(() -> orders.close(rc.po().id(), null)).isInstanceOf(RetailRuleViolation.class);

        SupplierInvoice approved = invoices.approveException(inv.id(), "Packaging surcharge agreed by buyer");
        assertThat(approved.status()).isEqualTo(InvoiceStatus.APPROVED);
        JournalEntry je = ledger.entry(approved.journalEntryId());
        // GR/IR at PO rate: GHS 20,000 / 15.42 = USD 1,297.02; AP at invoice rate: GHS 21,000 / 15.80 = 1,329.11;
        // price variance GHS 1,000 / 15.80 = 63.29; FX gain = 1,329.11 − 1,297.02 − 63.29 = −31.20 (credit 7100)
        assertThat(je.lines()).containsExactly(new JournalLine(GlAccount.GR_IR, 129_702, 0),
                new JournalLine(GlAccount.PURCHASE_PRICE_VARIANCE, 6_329, 0),
                new JournalLine(GlAccount.FX_GAIN_LOSS, 0, 3_120),
                new JournalLine(GlAccount.ACCOUNTS_PAYABLE, 0, 132_911));
        PurchaseOrder closed = orders.close(rc.po().id(), null);
        assertThat(closed.status()).isEqualTo(PoStatus.CLOSED);
        assertThat(invoices.get(inv.id()).status()).isEqualTo(InvoiceStatus.PAYMENT_RELEASED);
    }

    @Test
    void invoiceAheadOfGoodsIsHeldThenMatchedAfterReceipt() {
        Supplier supplier = approvedSupplier("GHS");
        RetailItem item = item(supplier, "Palm Oil, 1 L", null, 4_480);
        PurchaseOrder po = sentPo(supplier, List.of(new PoLineInput(item.id(), 200, null)));
        as(TestActors.FINANCE);
        SupplierInvoice held = invoices.register(new RegisterInvoiceCommand(po.id(), unique("INV-"), TODAY, null,
                null, List.of(new InvoiceLine(1, 200, 4_480))), unique("k-"));
        assertThat(held.status()).isEqualTo(InvoiceStatus.ON_HOLD);
        assertThat(held.fx().unitsPerBase()).isEqualByComparingTo("15.42");
        assertThat(outboxTypes(held.id())).containsExactly("retail.invoice.held");

        as(TestActors.RECEIVER);
        GoodsReceipt r = receiving.open(new OpenReceiptCommand(null, po.id(), "Dock 4"));
        String lot = unique("P");
        r = receiving.count(r.id(), List.of(new ReceiptLineInput(1, 1, lot, TODAY.minusDays(10),
                TODAY.plusDays(540), 200, 0, "B-06-01", QaResult.PASS, true)), r.version());
        Stubs.receiptAccepted(DOWNSTREAM, r.number(), List.of(new Stubs.Lot(item.sku(), lot, 200, "SELLABLE", 300)));
        receiving.complete(r.id());
        as(TestActors.FINANCE);
        SupplierInvoice matched = invoices.rematch(held.id());
        assertThat(matched.status()).isEqualTo(InvoiceStatus.MATCHED);
        assertThat(orders.get(po.id()).line(1).qtyInvoiced()).isEqualTo(200);
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameInvoiceAndDuplicatesAreRejected() {
        Received rc = received();
        String number = unique("INV-");
        String key = unique("k-");
        RegisterInvoiceCommand cmd = new RegisterInvoiceCommand(rc.po().id(), number, TODAY, "GHS", null,
                List.of(new InvoiceLine(1, 500, 2_000)));
        SupplierInvoice first = invoices.register(cmd, key);
        assertThat(first.status()).isEqualTo(InvoiceStatus.MATCHED);
        assertThat(invoices.register(cmd, key).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> invoices.register(cmd, unique("k-"))).isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("retail.invoice-duplicate");
        assertThatThrownBy(() -> invoices.register(new RegisterInvoiceCommand(rc.po().id(), unique("INV-"), TODAY,
                "GHS", null, List.of(new InvoiceLine(1, 1, 2_000))), key)).isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("retail.idempotency-key-reused");
    }

    @Test
    void shippedRetailUnitsPostCogsAtLotLandedCostOnce() {
        Received rc = received();
        UUID eventId = UUID.randomUUID();
        List<ShippedLine> lines = List.of(
                new ShippedLine(rc.item().sku(), 3, List.of(new ShippedLine.LotCost("L1", 2, 135, "USD"),
                        new ShippedLine.LotCost("L2", 1, 140, "USD"))),
                new ShippedLine("SELLER-SKU-1", 5, List.of(new ShippedLine.LotCost("S1", 5, 999, "USD"))));
        Optional<JournalEntry> posted = cogs.onShipped(eventId, "ORD-118204", lines);
        assertThat(posted).isPresent();
        assertThat(posted.get().lines()).containsExactly(new JournalLine(GlAccount.COGS, 410, 0),
                new JournalLine(GlAccount.INVENTORY, 0, 410));
        assertThat(cogs.onShipped(eventId, "ORD-118204", lines)).isEmpty();
        assertThat(cogs.onShipped(UUID.randomUUID(), "ORD-3P", List.of(lines.get(1)))).isEmpty();
    }

    @Test
    void lotsPaidInPesosPostCogsAtTheirUsdValueFixedAtReceiving() {
        Received rc = received();
        // 2 units from a Mexican lot (MXN 120.00 each, valued $6.92 when received) and 1 from a dollar lot.
        List<ShippedLine> lines = List.of(new ShippedLine(rc.item().sku(), 3, List.of(
                new ShippedLine.LotCost("L-MX", 2, 12_000, "MXN", 692L), new ShippedLine.LotCost("L-US", 1, 140, "USD"))));
        Optional<JournalEntry> posted = cogs.onShipped(UUID.randomUUID(), "ORD-MX-1", lines);
        assertThat(posted).isPresent();
        assertThat(posted.get().lines()).containsExactly(new JournalLine(GlAccount.COGS, 1_524, 0),
                new JournalLine(GlAccount.INVENTORY, 0, 1_524));
        List<ShippedLine> unknown = List.of(new ShippedLine(rc.item().sku(), 1, List.of(
                new ShippedLine.LotCost("L-OLD", 1, 5_000, "MXN"))));
        assertThat(cogs.onShipped(UUID.randomUUID(), "ORD-MX-2", unknown)).as("no USD value, no guess").isEmpty();
    }
}
