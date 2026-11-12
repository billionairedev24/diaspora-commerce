package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.ItemCondition;
import org.junit.jupiter.api.Test;

/** S393: offer rows. The match is given; the validator never fails a row because its page exists. */
class OfferImportValidatorTest {

    private static final String GTIN = ImportFixtures.VALID_GTIN;
    private static final List<String> HEADER = List.of("Barcode (UPC, EAN, ISBN)", "ATIN", "Brand", "Part number (MPN)",
            "Seller SKU", "Condition", "Condition note", "Price *", "List price", "Quantity", "Shipping charge",
            "Fulfilment", "Handling days");

    private static final OfferTarget FOUND = OfferTarget.match("AT00000002", "AT00000001", "Ankara tote", "org_a", true,
            List.of());

    private static String[] row(String gtin, String atin, String sku, String condition, String note, String price,
                                String list, String qty, String shipping, String fulfilment, String handling) {
        return new String[] {gtin, atin, "", "", sku, condition, note, price, list, qty, shipping, fulfilment, handling};
    }

    private static OfferImportContext context(Map<String, OfferTarget> targets) {
        return new OfferImportContext(targets, 5000);
    }

    private static OfferImportResult validate(Map<String, OfferTarget> targets, String[]... rows) {
        return OfferImportValidator.validate(ImportFixtures.sheet(HEADER, rows), context(targets));
    }

    @Test
    void aRowWhoseBarcodeMatchesAPageIsAnOfferRowNotAFailure() {
        OfferImportResult r = validate(Map.of("gtin:" + GTIN, FOUND),
                row(GTIN, "", "", "", "", "24.99", "", "10", "", "", "2"));

        assertThat(r.validation().rows()).singleElement().satisfies(v -> {
            assertThat(v.status()).isEqualTo(RowStatus.VALID);
            assertThat(v.groupKey()).isEqualTo("r2");
        });
        assertThat(r.importable()).singleElement().satisfies(o -> {
            assertThat(o.atin()).isEqualTo("AT00000002");
            assertThat(o.priceMinor()).isEqualTo(2499);
            assertThat(o.quantity()).isEqualTo(10);
            assertThat(o.condition()).isEqualTo(ItemCondition.NEW);
            assertThat(o.fulfilment()).isEqualTo(FulfilmentMode.SELLER);
            assertThat(o.handlingDays()).isEqualTo(2);
            assertThat(o.held()).isFalse();
        });
    }

    @Test
    void aMissingSkuGetsOneMadeFromTheAtinSoAFileUploadedAgainFindsItsOffers() {
        OfferImportResult first = validate(Map.of("gtin:" + GTIN, FOUND),
                row(GTIN, "", "", "", "", "24.99", "", "10", "", "", ""));
        OfferImportResult again = validate(Map.of("gtin:" + GTIN, FOUND),
                row(GTIN, "", "", "", "", "19.99", "", "3", "", "", ""));

        assertThat(first.importable().getFirst().sku()).isEqualTo("AT00000002");
        assertThat(again.importable().getFirst().sku()).isEqualTo("AT00000002");
        OfferImportResult used = validate(Map.of("gtin:" + GTIN, FOUND),
                row(GTIN, "", "", "Used - good", "Light scratch", "9.99", "", "1", "", "", ""));
        assertThat(used.importable().getFirst().sku()).isEqualTo("AT00000002-USED_GOOD");
    }

    @Test
    void theSameBarcodeTwiceInOneFileFirstRowWinsAndTheLaterRowIsAnError() {
        OfferImportResult r = validate(Map.of("gtin:" + GTIN, FOUND),
                row(GTIN, "", "", "", "", "24.99", "", "10", "", "", ""),
                row(GTIN, "", "", "", "", "20.00", "", "5", "", "", ""));

        assertThat(r.validation().rows()).extracting(ValidatedRow::status).containsExactly(RowStatus.VALID,
                RowStatus.ERROR);
        assertThat(r.validation().rows().get(1).summary()).startsWith("This barcode appears twice in your file.");
        assertThat(r.importable()).extracting(OfferRow::rowNumber).containsExactly(2);
    }

    @Test
    void theSameBarcodeInAnotherConditionIsAnotherOffer() {
        OfferImportResult r = validate(Map.of("gtin:" + GTIN, FOUND),
                row(GTIN, "", "", "", "", "24.99", "", "10", "", "", ""),
                row(GTIN, "", "", "Used - good", "Light scratch", "12.00", "", "1", "", "", ""));

        assertThat(r.validation().rows()).extracting(ValidatedRow::status).containsOnly(RowStatus.VALID);
    }

    @Test
    void aBarcodeThatDoesNotLookRightIsAnError() {
        OfferImportResult r = validate(Map.of(), row("4006381333932", "", "", "", "", "24.99", "", "10", "", "", ""));

        assertThat(r.validation().rows().getFirst().status()).isEqualTo(RowStatus.ERROR);
        assertThat(r.validation().rows().getFirst().summary()).startsWith("That barcode does not look right");
        assertThat(r.importable()).isEmpty();
    }

    @Test
    void aPageThatIsNotInTheCatalogIsAnErrorThatPointsToTheProductsTemplate() {
        OfferImportResult r = validate(Map.of("gtin:" + GTIN, OfferTarget.of(OfferTarget.Kind.NO_MATCH, null)),
                row(GTIN, "", "", "", "", "24.99", "", "10", "", "", ""));

        assertThat(r.validation().rows().getFirst().summary()).contains("Add it with the products template");
    }

    @Test
    void aBrandAndPartNumberNamesThePageToo() {
        OfferImportResult r = OfferImportValidator.validate(ImportFixtures.sheet(HEADER,
                new String[] {"", "", "Jolie", "JB-100", "", "", "", "12.00", "", "4", "", "", ""}),
                context(Map.of("mpn:jolie|JB100", FOUND)));

        assertThat(r.importable()).singleElement().extracting(OfferRow::atin).isEqualTo("AT00000002");
    }

    @Test
    void aPartNumberOnAPageWithSeveralChildrenAsksForTheBarcodeOrAtin() {
        OfferImportResult r = OfferImportValidator.validate(ImportFixtures.sheet(HEADER,
                new String[] {"", "", "Jolie", "JB-100", "", "", "", "12.00", "", "4", "", "", ""}),
                context(Map.of("mpn:jolie|JB100", OfferTarget.of(OfferTarget.Kind.AMBIGUOUS,
                        "This part number is on a page with several sizes or colours. Add the barcode or the ATIN of the "
                                + "one you sell."))));

        assertThat(r.validation().rows().getFirst().summary()).contains("Add the barcode or the ATIN");
    }

    @Test
    void aRowWithoutTheSellingApprovalIsHeldWithAWarningAndIsNeverSent() {
        OfferTarget gated = OfferTarget.match("AT00000002", "AT00000001", "Ankara tote", "org_a", false,
                List.of(new OfferTarget.Blocker("offers.selling-approval-required",
                        "You need approval to sell this brand. Apply under Listings.")));
        OfferImportResult r = validate(Map.of("gtin:" + GTIN, gated),
                row(GTIN, "", "", "", "", "24.99", "", "10", "", "", ""));

        assertThat(r.validation().rows().getFirst().status()).isEqualTo(RowStatus.WARNING);
        assertThat(r.validation().rows().getFirst().summary()).contains("You need approval to sell this brand")
                .contains("held");
        assertThat(r.importable()).singleElement().satisfies(o -> {
            assertThat(o.held()).isTrue();
            assertThat(o.heldNote()).contains("not for sale");
        });
    }

    @Test
    void aPageThatIsNotOnSaleHoldsTheRowButABrandThatIsRetiredFailsIt() {
        OfferTarget notLive = OfferTarget.match("AT00000002", "AT00000001", "Ankara tote", "org_a", false,
                List.of(new OfferTarget.Blocker("catalog.page-not-live", "This product page is not on sale yet.")));
        OfferTarget retired = OfferTarget.match("AT00000002", "AT00000001", "Ankara tote", "org_a", false,
                List.of(new OfferTarget.Blocker("catalog.brand-unavailable", "This brand is not available.")));

        OfferImportResult held = validate(Map.of("gtin:" + GTIN, notLive),
                row(GTIN, "", "", "", "", "24.99", "", "10", "", "", ""));
        OfferImportResult failed = validate(Map.of("gtin:" + GTIN, retired),
                row(GTIN, "", "", "", "", "24.99", "", "10", "", "", ""));

        assertThat(held.importable().getFirst().held()).isTrue();
        assertThat(failed.validation().rows().getFirst().status()).isEqualTo(RowStatus.ERROR);
        assertThat(failed.validation().rows().getFirst().summary()).isEqualTo("This brand is not available.");
    }

    @Test
    void contentColumnsAreIgnoredWithTheWarningAndNeverRequired() {
        List<String> header = List.of("Barcode", "Price", "Quantity", "Title", "Main image URL", "Description");
        OfferImportResult r = OfferImportValidator.validate(ImportFixtures.sheet(header,
                new String[] {GTIN, "24.99", "10", "A new title", "https://cdn.example.com/a.jpg", ""}),
                context(Map.of("gtin:" + GTIN, FOUND)));

        assertThat(r.validation().rows().getFirst().status()).isEqualTo(RowStatus.WARNING);
        assertThat(r.validation().rows().getFirst().summary()).contains(
                "This product is already in the catalog, so we used the existing photos and details.");
        assertThat(r.validation().fileWarnings()).singleElement().asString().contains("Title", "Main image URL");
        assertThat(r.importable()).hasSize(1);
    }

    @Test
    void theTermsAreChecked() {
        OfferImportResult r = validate(Map.of("gtin:" + GTIN, FOUND),
                row(GTIN, "", "A1", "Used - good", "", "24.99", "", "10", "", "", ""),
                row(GTIN, "", "A2", "", "", "abc", "", "10", "", "", ""),
                row(GTIN, "", "A3", "Renewed", "ok", "24.99", "10.00", "10", "", "", ""),
                row(GTIN, "", "A4", "Mint", "", "24.99", "", "10", "", "", ""),
                row(GTIN, "", "A5", "", "", "24.99", "", "", "", "", ""),
                row(GTIN, "", "A6", "Renewed", "x", "24.99", "", "1", "", "", "31"));

        List<String> messages = r.validation().rows().stream().map(ValidatedRow::summary).toList();
        assertThat(messages.get(0)).isEqualTo("Say what is different about this item");
        assertThat(messages.get(1)).isEqualTo("Not a price. Use dollars, for example 24.99");
        assertThat(messages.get(2)).isEqualTo("The list price must be at least the price");
        assertThat(messages.get(3)).startsWith("Use one of: New, Renewed");
        assertThat(messages.get(4)).isEqualTo("Give the quantity for an item you ship yourself");
        assertThat(messages.get(5)).isEqualTo("A whole number from 0 to 30");
    }

    @Test
    void fulfilledByAfroTransactIgnoresTheQuantityWithAWarning() {
        OfferImportResult r = validate(Map.of("gtin:" + GTIN, FOUND),
                row(GTIN, "", "", "", "", "24.99", "", "10", "", "Fulfilled by AfroTransact", ""));

        assertThat(r.validation().rows().getFirst().status()).isEqualTo(RowStatus.WARNING);
        assertThat(r.importable().getFirst().quantity()).isNull();
        assertThat(r.importable().getFirst().sku()).isEqualTo("AT00000002-FBA");
    }

    @Test
    void aFileOverTheRowCapOrWithoutPriceOrPageColumnsIsRefused() {
        assertThatThrownBy(() -> OfferImportValidator.validate(ImportFixtures.sheet(HEADER,
                row(GTIN, "", "", "", "", "1", "", "1", "", "", ""), row(GTIN, "", "", "", "", "1", "", "1", "", "", "")),
                new OfferImportContext(Map.of(), 1))).isInstanceOf(SpreadsheetRejected.class)
                .hasMessageContaining("upload at most 1 rows");
        assertThatThrownBy(() -> OfferImportValidator.validate(ImportFixtures.sheet(List.of("Barcode", "Quantity"),
                new String[] {GTIN, "1"}), context(Map.of()))).isInstanceOf(SpreadsheetRejected.class)
                .hasMessageContaining("no Price column");
        assertThatThrownBy(() -> OfferImportValidator.validate(ImportFixtures.sheet(List.of("Price", "Quantity"),
                new String[] {"1", "1"}), context(Map.of()))).isInstanceOf(SpreadsheetRejected.class)
                .hasMessageContaining("Barcode, ATIN, or Brand and Part number");
    }

    @Test
    void theOfferTemplateHasNoImageTitleOrAttributeColumnAndNothingPerCategory() {
        TemplateSpec spec = OfferImportColumns.offerTemplate();

        List<String> keys = spec.columns().stream().map(ImportColumn::key).toList();
        assertThat(keys).contains("gtin", "atin", "brand", "mpn", "seller_sku", "condition", "price", "list_price",
                "quantity", "shipping", "fulfilment", "handling_days");
        assertThat(keys).noneMatch(k -> k.startsWith("attr:") || k.startsWith("option:") || k.startsWith("fact:"));
        assertThat(spec.columns()).extracting(c -> c.label().toLowerCase()).noneMatch(l -> l.contains("image")
                || l.contains("photo") || l.equals("title") || l.equals("description") || l.equals("category"));
        assertThat(spec.columns()).filteredOn(ImportColumn::required).extracting(ImportColumn::key)
                .containsExactly("price");
        assertThat(spec.instructions()).anyMatch(i -> i.contains("held"));
    }

    @Test
    void theKeysOfASheetAreTheLookupsTheCallerMustMake() {
        ParsedSheet sheet = ImportFixtures.sheet(HEADER, row(GTIN, "", "", "", "", "1", "", "1", "", "", ""),
                row("", "at00000003", "", "", "", "1", "", "1", "", "", ""),
                row("", "", "", "", "", "1", "", "1", "", "", ""));

        assertThat(OfferImportValidator.keysOf(sheet)).extracting(TargetKey::id).containsExactly("gtin:" + GTIN,
                "atin:AT00000003");
    }
}
