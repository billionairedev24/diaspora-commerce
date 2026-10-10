package com.afrotransact.retail.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultReceiptValidatorTest {

    private static final LocalDate TODAY = Fixtures.TODAY;

    private final ReceiptValidator validator = new DefaultReceiptValidator(90, 0.5, BigDecimal.valueOf(2));
    private final Supplier supplier = Fixtures.approvedAccra();
    /** 18-month shelf life → minimum remaining life max(90, 274) = 274 days. */
    private final RetailItem jollof = Fixtures.item(supplier, "AT-JOL-6", "Jollof Seasoning", 548, StorageType.AMBIENT,
            "3.2");
    private final RetailItem chilled = Fixtures.item(supplier, "AT-PAL-1L", "Palm Oil", 365, StorageType.CHILLED, "0");

    private GoodsReceipt receipt(ReceiptLine... lines) {
        return new GoodsReceipt(UUID.randomUUID(), "GRN-2610-0001", UUID.randomUUID(), "PO-1P-323", null, null,
                "HOU-FC", "Dock 3", ReceiptStatus.OPEN, List.of(lines), List.of(), null, List.of(), null, List.of(),
                null, "dan", Fixtures.NOW, null, null, Fixtures.NOW, 0);
    }

    private ReceiptLine line(int lineNo, RetailItem item, int expected, String lot, LocalDate mfg, LocalDate exp,
                             int good, int damaged, String bin, QaResult qa) {
        return new ReceiptLine(lineNo, 1, 1, item.id(), item.sku(), expected, "L2609-JOL-04", lot, mfg, exp, good,
                damaged, bin, qa, true, false);
    }

    private ReceiptLine good(int expected, int good) {
        return line(1, jollof, expected, "L2609-JOL-04", TODAY.minusDays(20), TODAY.plusDays(500), good, 0,
                "R-01-02", QaResult.PASS);
    }

    private ReceiptValidation validate(GoodsReceipt r, Set<String> existing) {
        return validator.validate(r, Map.of(jollof.id(), jollof, chilled.id(), chilled), existing, TODAY);
    }

    @Test
    void cleanBatchIsSellable() {
        ReceiptValidation v = validate(receipt(good(900, 900)), Set.of());
        assertThat(v.ok()).isTrue();
        assertThat(v.line(1).errors()).isEmpty();
        assertThat(v.line(1).warnings()).isEmpty();
        assertThat(v.line(1).disposition()).isEqualTo(Disposition.SELLABLE);
        assertThat(v.line(1).remainingDays()).isEqualTo(500);
    }

    @Test
    void lotDatesBinAndQaAreRequired() {
        ReceiptLine bad = line(1, jollof, 900, "L1", null, null, 900, 0, "bin 4", QaResult.PENDING);
        ReceiptValidation v = validate(receipt(bad), Set.of());
        assertThat(v.ok()).isFalse();
        assertThat(v.line(1).errors()).contains("Lot must be 4–20 letters, digits or dashes",
                "Manufacture date is required", "Expiry date is required", "Put-away bin must look like R-01-02",
                "QA inspection result needed");
        assertThat(v.fieldErrors()).containsKey("lines[1]");
    }

    @Test
    void impossibleOrPassedDatesAreRejected() {
        ReceiptValidation future = validate(receipt(line(1, jollof, 10, "LOT-1", TODAY.plusDays(1),
                TODAY.plusDays(400), 10, 0, "R-01-01", QaResult.PASS)), Set.of());
        assertThat(future.line(1).errors()).contains("Manufacture date is in the future");
        ReceiptValidation inverted = validate(receipt(line(1, jollof, 10, "LOT-1", TODAY.minusDays(5),
                TODAY.minusDays(6), 10, 0, "R-01-01", QaResult.PASS)), Set.of());
        assertThat(inverted.line(1).errors()).contains("Expiry must be after manufacture date",
                "Already expired — reject at dock");
    }

    @Test
    void shortDatedBatchesGoToQuarantine() {
        ReceiptValidation v = validate(receipt(line(1, jollof, 10, "LOT-1", TODAY.minusDays(300),
                TODAY.plusDays(200), 10, 0, "R-01-01", QaResult.PASS)), Set.of());
        assertThat(v.ok()).isTrue();
        assertThat(v.line(1).disposition()).isEqualTo(Disposition.QUARANTINE);
        assertThat(v.line(1).warnings()).anyMatch(w -> w.contains("Short-dated: 200 days left, minimum 274"));
    }

    @Test
    void ninetyDayFloorAppliesToShortShelfLifeItems() {
        RetailItem fresh = Fixtures.item(supplier, "AT-FRS-1", "Fresh Pepper", 60, StorageType.AMBIENT, "0");
        assertThat(fresh.minRemainingLifeDays(90, 0.5)).isEqualTo(90);
        assertThat(jollof.minRemainingLifeDays(90, 0.5)).isEqualTo(274);
    }

    @Test
    void qaFailureQuarantinesTheWholeBatch() {
        ReceiptValidation v = validate(receipt(line(1, jollof, 10, "LOT-1", TODAY.minusDays(5), TODAY.plusDays(500),
                10, 0, "R-01-01", QaResult.FAIL)), Set.of());
        assertThat(v.ok()).isTrue();
        assertThat(v.line(1).disposition()).isEqualTo(Disposition.QUARANTINE);
    }

    @Test
    void lotsAreUniquePerSkuAcrossReceiptsAndWithinTheReceipt() {
        ReceiptValidation again = validate(receipt(good(900, 900)), Set.of("AT-JOL-6|L2609-JOL-04"));
        assertThat(again.line(1).errors()).contains("Lot L2609-JOL-04 already exists for this SKU");
        ReceiptLine split = new ReceiptLine(2, 1, 1, jollof.id(), jollof.sku(), 0, "L2609-JOL-04", "L2609-JOL-04",
                TODAY.minusDays(20), TODAY.plusDays(500), 5, 0, "R-01-03", QaResult.PASS, true, true);
        ReceiptValidation twice = validate(receipt(good(900, 895), split), Set.of());
        assertThat(twice.line(2).errors()).anyMatch(e -> e.contains("appears twice"));
    }

    @Test
    void chilledItemsMustGoToChilledBins() {
        ReceiptLine ambientBin = line(1, chilled, 10, "LOT-9", TODAY.minusDays(5), TODAY.plusDays(300), 10, 0,
                "R-01-01", QaResult.PASS);
        assertThat(validate(receipt(ambientBin), Set.of()).line(1).errors())
                .contains("CHILLED items go to K- bins");
        ReceiptLine chilledBin = line(1, chilled, 10, "LOT-9", TODAY.minusDays(5), TODAY.plusDays(300), 10, 0,
                "K-01-01", QaResult.PASS);
        assertThat(validate(receipt(chilledBin), Set.of()).ok()).isTrue();
    }

    @Test
    void moreThanTwoPercentOverIsBlockedUntilApproved() {
        assertThat(validate(receipt(good(1000, 1020)), Set.of()).ok()).isTrue();
        ReceiptValidation over = validate(receipt(good(1000, 1021)), Set.of());
        assertThat(over.ok()).isFalse();
        assertThat(over.variances().getFirst().overBlocked()).isTrue();
        assertThat(over.fieldErrors()).containsKey("poLine[1]");
        GoodsReceipt approved = receipt(good(1000, 1021)).approveOverReceipt("lead", Fixtures.NOW);
        assertThat(validate(approved, Set.of()).ok()).isTrue();
    }

    @Test
    void emptyReceiptCannotComplete() {
        ReceiptValidation v = validate(receipt(good(900, 0)), Set.of());
        assertThat(v.ok()).isFalse();
        assertThat(v.receiptErrors()).contains("Nothing has been counted yet");
    }

    @Test
    void warnsAboutManualEntryAsnMismatchAndDamage() {
        ReceiptLine keyed = new ReceiptLine(1, 1, 1, jollof.id(), jollof.sku(), 900, "L2609-JOL-04", "L2609-JOL-99",
                TODAY.minusDays(20), TODAY.plusDays(500), 880, 20, "R-01-02", QaResult.PASS, false, false);
        LineCheck c = validate(receipt(keyed), Set.of()).line(1);
        assertThat(c.errors()).isEmpty();
        assertThat(c.warnings()).anyMatch(w -> w.startsWith("Keyed manually"))
                .anyMatch(w -> w.contains("differs from supplier ASN"))
                .anyMatch(w -> w.startsWith("20 damaged"));
    }

    @Test
    void discrepanciesRaiseClaimsForShortagesAndDamage() {
        ReceiptLine shortAndDamaged = new ReceiptLine(1, 1, 1, jollof.id(), jollof.sku(), 900, null, "LOT-5",
                TODAY.minusDays(20), TODAY.plusDays(500), 850, 30, "R-01-02", QaResult.PASS, true, false);
        GoodsReceipt r = receipt(shortAndDamaged);
        List<Discrepancy> found = validator.discrepancies(r, validate(r, Set.of()));
        assertThat(found).extracting(Discrepancy::type).containsExactly(DiscrepancyType.SHORT, DiscrepancyType.DAMAGED);
        assertThat(found.getFirst().qty()).isEqualTo(20);
        assertThat(found.getFirst().claimAgainst()).isEqualTo("SUPPLIER");
        assertThat(found.get(1).qty()).isEqualTo(30);
        assertThat(found.get(1).claimAgainst()).isEqualTo("CARRIER");
        assertThat(found).allMatch(d -> d.status() == ClaimStatus.OPEN);
        assertThat(found).extracting(Discrepancy::claimRef).containsExactly("CLM-GRN-2610-0001-1",
                "CLM-GRN-2610-0001-2");

        GoodsReceipt over = receipt(good(1000, 1010));
        assertThat(validator.discrepancies(over, validate(over, Set.of()))).singleElement()
                .satisfies(d -> {
                    assertThat(d.type()).isEqualTo(DiscrepancyType.OVER);
                    assertThat(d.status()).isEqualTo(ClaimStatus.NOT_REQUIRED);
                });
    }
}
