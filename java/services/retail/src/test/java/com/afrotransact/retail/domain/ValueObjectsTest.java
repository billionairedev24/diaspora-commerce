package com.afrotransact.retail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ValueObjectsTest {

    @Test
    void issuesGtinsFromTheAfroTransactPrefixWithCheckDigit() {
        String gtin = Gtin.issue("86001", 200061);
        assertThat(gtin).hasSize(12).startsWith("86001200061");
        assertThat(Gtin.valid(gtin)).isTrue();
        assertThat(Gtin.toGtin14(gtin)).isEqualTo("00" + gtin);
        assertThat(Gtin.valid("4006381333931")).isTrue();
        assertThat(Gtin.valid("4006381333932")).isFalse();
        assertThatThrownBy(() -> Gtin.issue("86001", 1_000_000)).isInstanceOf(RetailRuleViolation.class);
    }

    @Test
    void convertsFromBaseToThePoCurrencyAndStatesTheRateToUsd() {
        FxRate ghs = new FxRate("GHS", "USD", new BigDecimal("15.42"), Fixtures.TODAY);
        assertThat(ghs.fromBase(100)).isEqualTo(1_542);         // USD 1.00 → GHS 15.42
        assertThat(ghs.baseUnitsPerUnit()).isEqualByComparingTo("0.0648508431");
        FxRate xof = new FxRate("XOF", "USD", new BigDecimal("566"), Fixtures.TODAY);
        assertThat(xof.fromBase(100)).isEqualTo(566);
        assertThat(FxRate.identity("USD", Fixtures.TODAY).fromBase(12_345)).isEqualTo(12_345);
    }

    @Test
    void convertsBetweenCurrenciesWithDifferentMinorUnits() {
        FxRate ghs = new FxRate("GHS", "USD", new BigDecimal("15.42"), Fixtures.TODAY);
        assertThat(ghs.toBase(1_542)).isEqualTo(100);          // GHS 15.42 → USD 1.00
        assertThat(ghs.toBase(-1_542)).isEqualTo(-100);
        FxRate xof = new FxRate("XOF", "USD", new BigDecimal("566"), Fixtures.TODAY);
        assertThat(Currencies.fractionDigits("XOF")).isZero();
        assertThat(xof.toBase(566)).isEqualTo(100);             // XOF 566 (no minor unit) → USD 1.00
        assertThat(xof.toBase(1)).isEqualTo(0);
        FxRate usd = FxRate.identity("USD", Fixtures.TODAY);
        assertThat(usd.toBase(12_345)).isEqualTo(12_345);
        assertThatThrownBy(() -> new FxRate("GHS", "USD", BigDecimal.ZERO, Fixtures.TODAY))
                .isInstanceOf(RetailRuleViolation.class);
        assertThatThrownBy(() -> Currencies.require("ABC")).isInstanceOf(RetailRuleViolation.class);
    }

    @Test
    void journalEntriesMustBalanceAndDropZeroLines() {
        GlPostingRules rules = new DefaultGlPostingRules();
        JournalEntry departed = new JournalEntry(UUID.randomUUID(), "JE-1", Fixtures.TODAY,
                JournalSource.SHIPMENT_DEPARTED, "SHP-1", "Title transfer", "USD", rules.titleTransfer(1_842_000),
                "ops", Fixtures.NOW);
        assertThat(departed.lines()).extracting(l -> l.account().code()).containsExactly("1310", "2110");
        assertThat(departed.amountMinor()).isEqualTo(1_842_000);

        List<JournalLine> received = rules.goodsReceived(900, 100, 700, 300, true);
        assertThat(received).extracting(l -> l.account().code()).containsExactly("1300", "1320", "1310", "2120");
        assertThat(rules.goodsReceived(1000, 0, 700, 300, false).get(2).account()).isEqualTo(GlAccount.GR_IR);
        JournalEntry noQuarantine = new JournalEntry(UUID.randomUUID(), "JE-2", Fixtures.TODAY,
                JournalSource.GOODS_RECEIPT, "GRN-1", null, "USD", rules.goodsReceived(1000, 0, 700, 300, true),
                "dan", Fixtures.NOW);
        assertThat(noQuarantine.lines()).hasSize(3);
        assertThatThrownBy(() -> rules.goodsReceived(1000, 0, 700, 299, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), "JE-3", Fixtures.TODAY, JournalSource.COGS, "x",
                null, "USD", List.of(JournalLine.debit(GlAccount.COGS, 5), JournalLine.credit(GlAccount.INVENTORY, 4)),
                "x", Fixtures.NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invoiceMatchPostsPriceVarianceAndFxDifference() {
        GlPostingRules rules = new DefaultGlPostingRules();
        // GR/IR cleared at PO rate 1000, price variance +10, AP at invoice rate 980 → FX gain 30 (credit 7100)
        List<JournalLine> lines = rules.invoiceMatched(1_000, 10, 980);
        assertThat(lines).containsExactly(new JournalLine(GlAccount.GR_IR, 1_000, 0),
                new JournalLine(GlAccount.PURCHASE_PRICE_VARIANCE, 10, 0),
                new JournalLine(GlAccount.FX_GAIN_LOSS, 0, 30),
                new JournalLine(GlAccount.ACCOUNTS_PAYABLE, 0, 980));
        JournalEntry e = new JournalEntry(UUID.randomUUID(), "JE-4", Fixtures.TODAY, JournalSource.INVOICE_MATCH,
                "INV-1", null, "USD", lines, "fin", Fixtures.NOW);
        assertThat(e.amountMinor()).isEqualTo(1_010);
    }

    @Test
    void supplierLifecycleAndScorecard() {
        Supplier s = Supplier.onboard(UUID.randomUUID(), "sup-dkg", "Dakar Grains SARL", "sn", "Dakar", "XOF",
                "Net 30", 30, List.of("Ecocert Organic"), List.of(), Fixtures.NOW);
        assertThat(s.code()).isEqualTo("SUP-DKG");
        assertThat(s.status()).isEqualTo(SupplierStatus.ONBOARDING);
        assertThatThrownBy(() -> s.changeStatus(SupplierStatus.ON_WATCH, "late", Fixtures.NOW))
                .isInstanceOf(RetailStateConflict.class);
        Supplier approved = s.changeStatus(SupplierStatus.APPROVED, null, Fixtures.NOW);
        assertThatThrownBy(() -> approved.changeStatus(SupplierStatus.INACTIVE, " ", Fixtures.NOW))
                .isInstanceOf(RetailRuleViolation.class);
        Supplier scored = approved.recordReceipt(true, 1_000, 6, Fixtures.NOW)
                .recordReceipt(false, 1_000, 14, Fixtures.NOW);
        assertThat(scored.scorecard().onTimePercent()).isEqualByComparingTo("50.0");
        assertThat(scored.scorecard().defectPercent()).isEqualByComparingTo("1.0");
        assertThat(SupplierScorecard.EMPTY.onTimePercent()).isNull();
        assertThatThrownBy(() -> Supplier.onboard(UUID.randomUUID(), "ACC", "x", "GH", null, "GHS", null, 1, null,
                null, Fixtures.NOW)).isInstanceOf(RetailRuleViolation.class);
    }

    @Test
    void itemListingStartsAsDraftAndGoesLiveWithAtinAndPrice() {
        Supplier supplier = Fixtures.approvedAccra();
        RetailItem item = Fixtures.item(supplier, "AT-JOL-200061", "AfroTransact Brand Jollof Seasoning, 6 oz", 548,
                StorageType.AMBIENT, "3.2");
        assertThat(item.listingStatus()).isEqualTo(ListingStatus.DRAFT);
        assertThatThrownBy(() -> item.changeListing(ListingStatus.LIVE, Fixtures.NOW))
                .isInstanceOf(RetailRuleViolation.class).extracting("code").isEqualTo("retail.listing-incomplete");
        RetailItem live = item.linkAtin("AT0000A1B2", Fixtures.NOW).changeListing(ListingStatus.LIVE, Fixtures.NOW);
        assertThat(live.listingStatus()).isEqualTo(ListingStatus.LIVE);
        assertThat(live.changeListing(ListingStatus.PAUSED, Fixtures.NOW).listingStatus())
                .isEqualTo(ListingStatus.PAUSED);
        assertThatThrownBy(() -> live.changeListing(ListingStatus.DRAFT, Fixtures.NOW))
                .isInstanceOf(RetailStateConflict.class);
        assertThat(RetailItem.skuFor("AfroTransact Brand Jollof Seasoning, 6 oz", 200061)).isEqualTo("AT-JOL-200061");
        assertThat(RetailItem.skuFor("Shito Black Pepper Sauce", 200124)).isEqualTo("AT-SHI-200124");
        assertThatThrownBy(() -> new RetailItem(UUID.randomUUID(), "AT-X-1", 1, "1", "Valid name", null, null,
                supplier.id(), List.of(new SupplierItem(supplier.id(), "S1", 24, 0, 1, "GHS")), "GH", "0910.91",
                BigDecimal.ONE, 100, StorageType.AMBIENT, 1, null, 5, 24, 1, 1, 1, "USD", ListingStatus.DRAFT,
                Fixtures.NOW, Fixtures.NOW, 0)).isInstanceOf(RetailRuleViolation.class).hasMessageContaining("Case pack");
        assertThatThrownBy(() -> new RetailItem(UUID.randomUUID(), "AT-X-1", 1, "1", "Valid name", null, null,
                UUID.randomUUID(), List.of(new SupplierItem(supplier.id(), "S1", 24, 0, 1, "GHS")), "GH", "0910.91",
                BigDecimal.ONE, 100, StorageType.AMBIENT, 1, null, 6, 24, 1, 1, 1, "USD", ListingStatus.DRAFT,
                Fixtures.NOW, Fixtures.NOW, 0)).isInstanceOf(RetailRuleViolation.class)
                .hasMessageContaining("primary supplier");
    }
}
