package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** S394: the price and quantity feed. Blank means unchanged; up to 50,000 rows. */
class PriceQtyValidatorTest {

    private static final List<String> HEADER = List.of("Seller SKU", "ATIN", "Price", "List price", "Quantity");

    private static PriceQtyResult validate(String[]... rows) {
        return PriceQtyValidator.validate(ImportFixtures.sheet(HEADER, rows), ImportLimits.MAX_FEED_ROWS);
    }

    @Test
    void blankCellsMeanUnchanged() {
        PriceQtyResult r = validate(new String[] {"sku-1", "", "22.50", "", ""}, new String[] {"SKU-2", "", "", "", "8"},
                new String[] {"", "AT0000ABCD", "", "0", ""});

        assertThat(r.importable()).hasSize(3);
        PriceQtyRow first = r.importable().get(0);
        assertThat(first.sku()).isEqualTo("SKU-1");
        assertThat(first.priceMinor()).isEqualTo(2250);
        assertThat(first.listPriceMinor()).isNull();
        assertThat(first.quantity()).isNull();
        assertThat(r.importable().get(1).quantity()).isEqualTo(8);
        assertThat(r.importable().get(1).priceMinor()).isNull();
        PriceQtyRow byAtin = r.importable().get(2);
        assertThat(byAtin.atin()).isEqualTo("AT0000ABCD");
        assertThat(byAtin.sku()).isNull();
        assertThat(byAtin.listPriceMinor()).isZero();
    }

    @Test
    void goodRowsProceedAndBadRowsAreErrorsWithPlainMessages() {
        PriceQtyResult r = validate(new String[] {"A", "", "10.00", "", "5"}, new String[] {"B", "", "free", "", ""},
                new String[] {"C", "", "10.00", "5.00", ""}, new String[] {"D", "", "", "", "-1"},
                new String[] {"", "", "1.00", "", ""}, new String[] {"E", "XYZ", "1.00", "", ""});

        assertThat(r.validation().rows()).extracting(ValidatedRow::status).containsExactly(RowStatus.VALID,
                RowStatus.ERROR, RowStatus.ERROR, RowStatus.ERROR, RowStatus.ERROR, RowStatus.ERROR);
        assertThat(r.validation().rows().get(1).summary()).isEqualTo("Not a price. Use dollars, for example 24.99");
        assertThat(r.validation().rows().get(2).summary()).isEqualTo("The list price must be at least the price");
        assertThat(r.validation().rows().get(3).summary()).isEqualTo("A whole number from 0 to 99999");
        assertThat(r.validation().rows().get(4).summary()).isEqualTo("Give the seller SKU or the ATIN");
        assertThat(r.validation().rows().get(5).summary()).startsWith("That ATIN does not look right");
        assertThat(r.importable()).extracting(PriceQtyRow::sku).containsExactly("A");
    }

    @Test
    void aBarcodeIsAcceptedOnlyForARowWithoutASkuOrAtin() {
        List<String> header = List.of("Seller SKU", "ATIN", "Barcode (UPC, EAN, ISBN)", "Price");
        PriceQtyResult r = PriceQtyValidator.validate(ImportFixtures.sheet(header,
                new String[] {"", "", "036000291452", "5.00"}, new String[] {"SKU-1", "", "036000291452", "5.00"},
                new String[] {"", "", "12345", "5.00"}, new String[] {"", "", "0036000291452", "6.00"}), 100);

        assertThat(r.importable()).hasSize(2);
        PriceQtyRow byBarcode = r.importable().get(0);
        assertThat(byBarcode.sku()).isNull();
        assertThat(byBarcode.atin()).isNull();
        assertThat(byBarcode.gtin()).isEqualTo("036000291452");
        // The SKU is the key: the barcode on a row that has one is ignored.
        PriceQtyRow bySku = r.importable().get(1);
        assertThat(bySku.sku()).isEqualTo("SKU-1");
        assertThat(bySku.gtin()).isNull();
        assertThat(r.validation().rows().get(2).summary()).isEqualTo("That barcode does not look right. Use your SKU instead");
        // The UPC-A and its GTIN-13 form are the same barcode: the second is a duplicate of the first.
        assertThat(r.validation().rows().get(3).summary()).startsWith("This barcode appears twice in your file. Row 2");
    }

    @Test
    void aFileWithOnlyABarcodeColumnIsAccepted() {
        PriceQtyResult r = PriceQtyValidator.validate(ImportFixtures.sheet(List.of("Barcode", "Price"),
                new String[] {"036000291452", "5.00"}), 100);

        assertThat(r.importable()).singleElement().extracting(PriceQtyRow::gtin).isEqualTo("036000291452");
    }

    @Test
    void theSameSkuTwiceFirstRowWins() {
        PriceQtyResult r = validate(new String[] {"A", "", "10.00", "", ""}, new String[] {"a", "", "9.00", "", ""});

        assertThat(r.validation().rows().get(1).status()).isEqualTo(RowStatus.ERROR);
        assertThat(r.validation().rows().get(1).summary()).isEqualTo(
                "This SKU appears twice in your file. Row 2 is used and this one is left out");
        assertThat(r.importable()).hasSize(1);
    }

    @Test
    void aRowWithNothingToChangeIsAWarningNotAnError() {
        PriceQtyResult r = validate(new String[] {"A", "", "", "", ""});

        assertThat(r.validation().rows().getFirst().status()).isEqualTo(RowStatus.WARNING);
        assertThat(r.importable()).hasSize(1);
    }

    @Test
    void fiftyThousandRowsAreAcceptedAndOneMoreIsRefused() {
        List<SheetRow> rows = new ArrayList<>();
        for (int i = 0; i < ImportLimits.MAX_FEED_ROWS; i++) {
            rows.add(new SheetRow(i + 2, List.of("SKU-" + i, "", "10.00", "", "1")));
        }
        PriceQtyResult ok = PriceQtyValidator.validate(new ParsedSheet(HEADER, rows), ImportLimits.MAX_FEED_ROWS);
        assertThat(ok.importable()).hasSize(50_000);
        assertThat(ok.validation().count(RowStatus.ERROR)).isZero();

        rows.add(new SheetRow(50_002, List.of("SKU-X", "", "10.00", "", "1")));
        assertThatThrownBy(() -> PriceQtyValidator.validate(new ParsedSheet(HEADER, rows), ImportLimits.MAX_FEED_ROWS))
                .isInstanceOf(SpreadsheetRejected.class).hasMessageContaining("upload at most 50000 rows");
    }

    @Test
    void aFileWithoutAnIdentityOrAChangeColumnIsRefused() {
        assertThatThrownBy(() -> PriceQtyValidator.validate(ImportFixtures.sheet(List.of("Price"),
                new String[] {"1"}), 100)).isInstanceOf(SpreadsheetRejected.class).hasMessageContaining("Seller SKU column");
        assertThatThrownBy(() -> PriceQtyValidator.validate(ImportFixtures.sheet(List.of("Seller SKU"),
                new String[] {"A"}), 100)).isInstanceOf(SpreadsheetRejected.class)
                .hasMessageContaining("Price, List price or Quantity");
    }

    @Test
    void theFeedTemplateHasOnlyTheFeedColumns() {
        TemplateSpec spec = OfferImportColumns.priceQtyTemplate();

        assertThat(spec.columns()).extracting(ImportColumn::key).containsExactly("seller_sku", "atin", "gtin", "price",
                "list_price", "quantity");
        assertThat(spec.instructions()).anyMatch(i -> i.contains("Seller SKU") && i.contains("exactly one of your listings"));
        assertThat(spec.instructions()).anyMatch(i -> i.contains("50,000 rows"));
    }
}
