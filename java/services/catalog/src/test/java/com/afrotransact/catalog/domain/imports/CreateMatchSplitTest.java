package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** S393: a CREATE row whose barcode matches becomes an offer; a mixed parent group splits into offers and requests. */
class CreateMatchSplitTest {

    private static final String G1 = ImportFixtures.VALID_GTIN;
    private static final String G2 = "4006381333948";
    private static final String G3 = "4006381333955";
    private static final List<String> HEADER = List.of("Parent SKU", "SKU", "Title", "Barcode (GTIN/UPC/EAN)", "Price (USD)",
            "Quantity");

    private static String[] row(String parent, String sku, String gtin) {
        return new String[] {parent, sku, "A title long enough", gtin, "10.00", "5"};
    }

    private static OfferTarget on(String atin, String owner) {
        return OfferTarget.match(atin, "AT00000001", "Page", owner, true, List.of());
    }

    @Test
    void aSingleRowWithAMatchingBarcodeBecomesAnOfferRowAndTheRestAreCreated() {
        ParsedSheet sheet = ImportFixtures.sheet(HEADER, row("", "A", G1), row("", "B", G2));

        CreateMatchSplit.Result r = CreateMatchSplit.split(sheet, Map.of("gtin:" + G1, on("AT00000002", "org_a")), "org_b");

        assertThat(r.offers()).extracting(SheetRow::rowNumber).containsExactly(2);
        assertThat(r.rest()).extracting(SheetRow::rowNumber).containsExactly(3);
        assertThat(r.variants()).isEmpty();
        assertThat(r.matched().get(2).atin()).isEqualTo("AT00000002");
        assertThat(r.any()).isTrue();
    }

    @Test
    void aBarcodeOnThesellersOwnPageIsNotAnOfferRow() {
        ParsedSheet sheet = ImportFixtures.sheet(HEADER, row("", "A", G1));

        CreateMatchSplit.Result r = CreateMatchSplit.split(sheet, Map.of("gtin:" + G1, on("AT00000002", "org_b")), "org_b");

        assertThat(r.any()).isFalse();
        assertThat(r.rest()).hasSize(1);
    }

    @Test
    void aParentGroupWithMatchedAndUnmatchedChildrenGivesOffersAndNewVariantRows() {
        ParsedSheet sheet = ImportFixtures.sheet(HEADER, row("PARENT-1", "A-S", G1), row("PARENT-1", "A-M", G2),
                row("PARENT-1", "A-L", G3), row("OTHER", "B-S", "4006381333962"));

        CreateMatchSplit.Result r = CreateMatchSplit.split(sheet, Map.of("gtin:" + G1, on("AT00000002", "org_a"),
                "gtin:" + G2, on("AT00000003", "org_a")), "org_b");

        assertThat(r.offers()).extracting(SheetRow::rowNumber).containsExactly(2, 3);
        assertThat(r.variants()).extracting(SheetRow::rowNumber).containsExactly(4);
        assertThat(r.rest()).extracting(SheetRow::rowNumber).containsExactly(5);
    }

    @Test
    void aGroupWithNoMatchIsCreatedWhole() {
        ParsedSheet sheet = ImportFixtures.sheet(HEADER, row("PARENT-1", "A-S", G1), row("PARENT-1", "A-M", G2));

        CreateMatchSplit.Result r = CreateMatchSplit.split(sheet, Map.of(), "org_b");

        assertThat(r.rest()).hasSize(2);
        assertThat(r.any()).isFalse();
    }

    @Test
    void theKeysAreTheValidBarcodesOfTheFile() {
        ParsedSheet sheet = ImportFixtures.sheet(HEADER, row("", "A", G1), row("", "B", "123"), row("", "C", ""));

        assertThat(CreateMatchSplit.keysOf(sheet)).extracting(TargetKey::id).containsExactly("gtin:" + G1);
    }

    @Test
    void theSameRowHasTheSameContentHashAndAChangedCellChangesIt() {
        List<String> a = List.of("PARENT", "SKU-1", " Title ", "", "");
        List<String> same = List.of("PARENT", "SKU-1", "Title");
        List<String> changed = List.of("PARENT", "SKU-1", "Title 2");

        assertThat(RowHashes.of(a)).isEqualTo(RowHashes.of(same)).hasSize(64).isNotEqualTo(RowHashes.of(changed));
    }
}
