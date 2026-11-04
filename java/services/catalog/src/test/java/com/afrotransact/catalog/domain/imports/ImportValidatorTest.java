package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.ItemCondition;
import org.junit.jupiter.api.Test;

/** Row validation of bulk uploads: every error type, variants, update mode and duplicate SKUs. */
class ImportValidatorTest {

    private static final List<String> DRESS_HEADER = List.of("Parent SKU", "SKU *", "Title *", "Brand *",
            "Image URLs *", "Description", "Price (USD) *", "Compare-at price (USD)", "Quantity *",
            "Barcode (GTIN/UPC/EAN)", "No barcode", "Variation", "Size", "Color", "Material *", "Care", "Fulfilment",
            "Package length (in)", "Package width (in)", "Package height (in)", "Package weight (lb)");

    /** A complete, valid dress row; tests override cells. */
    private static Map<String, String> dress(String sku) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("Parent SKU", "");
        r.put("SKU *", sku);
        r.put("Title *", "Ankara Wrap Dress, Puff Sleeve");
        r.put("Brand *", "Adire Lagos");
        r.put("Image URLs *", ImportFixtures.IMG);
        r.put("Description", "Hand-finished wax print.");
        r.put("Price (USD) *", "49.00");
        r.put("Quantity *", "12");
        r.put("No barcode", "Yes");
        r.put("Material *", "Lace");
        return r;
    }

    private static ParsedSheet sheet(List<String> header, List<Map<String, String>> rows) {
        List<SheetRow> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> r = rows.get(i);
            out.add(new SheetRow(i + 2, header.stream().map(h -> r.getOrDefault(h, "")).toList()));
        }
        return new ParsedSheet(header, out);
    }

    private static ValidationResult validate(ImportContext ctx, List<Map<String, String>> rows) {
        return ImportValidator.validate(sheet(DRESS_HEADER, rows), ctx);
    }

    private static List<String> errors(ValidatedRow r) {
        return r.messages().stream().filter(m -> m.severity() == Severity.ERROR)
                .map(m -> m.column() + ": " + m.message()).toList();
    }

    private static List<String> warnings(ValidatedRow r) {
        return r.messages().stream().filter(m -> m.severity() == Severity.WARNING)
                .map(m -> m.column() + ": " + m.message()).toList();
    }

    @Test
    void aValidSimpleProductBecomesOneImportableProduct() {
        ValidationResult res = validate(ImportFixtures.create(ImportFixtures.DRESS), List.of(dress("awd-1")));
        ValidatedRow row = res.rows().getFirst();
        assertThat(errors(row)).isEmpty();
        assertThat(row.status()).isEqualTo(RowStatus.VALID);
        assertThat(row.sku()).isEqualTo("AWD-1");
        ImportedProduct p = res.products().getFirst();
        assertThat(p.category()).isEqualTo(ImportFixtures.DRESSES);
        assertThat(p.variationTheme()).isEmpty();
        assertThat(p.attributes()).containsEntry("brand", "Adire Lagos").containsEntry("material", "Lace");
        assertThat(p.variants().getFirst().priceMinor()).isEqualTo(4900);
        assertThat(p.variants().getFirst().gtinExempt()).isTrue();
        assertThat(p.condition()).isNull();
        assertThat(res.productCount()).isEqualTo(1);
    }

    @Test
    void requiredFieldsEnumsNumbersAndImageLinksAreChecked() {
        Map<String, String> r = dress("AWD-2");
        r.put("Title *", "Short");
        r.put("Brand *", "");
        r.put("Material *", "Velvet");
        r.put("Price (USD) *", "abc");
        r.put("Quantity *", "-3");
        r.put("Image URLs *", "http://insecure.example.com/a.jpg ftp://x");
        ValidatedRow row = validate(ImportFixtures.create(ImportFixtures.DRESS), List.of(r)).rows().getFirst();
        assertThat(row.status()).isEqualTo(RowStatus.ERROR);
        assertThat(errors(row)).contains(
                "title: Between 10 and 200 characters",
                "brand: Required",
                "attr:material: Choose one of [Cotton wax print, Lace, Silk]",
                "price: A price above zero in US dollars, e.g. 24.99",
                "quantity: A whole number from 0 to 100,000",
                "image_urls: Use an https:// link: http://insecure.example.com/a.jpg");
        assertThat(row.messages()).filteredOn(m -> "price".equals(m.column()))
                .allSatisfy(m -> assertThat(m.columnIndex()).isEqualTo(DRESS_HEADER.indexOf("Price (USD) *")));
    }

    @Test
    void barcodesNeedAValidCheckDigitOrAnExemption() {
        Map<String, String> bad = dress("AWD-3");
        bad.put("No barcode", "");
        bad.put("Barcode (GTIN/UPC/EAN)", "4006381333932");
        Map<String, String> missing = dress("AWD-4");
        missing.put("No barcode", "No");
        Map<String, String> good = dress("AWD-5");
        good.put("No barcode", "");
        good.put("Barcode (GTIN/UPC/EAN)", ImportFixtures.VALID_GTIN);
        List<ValidatedRow> rows = validate(ImportFixtures.create(ImportFixtures.DRESS), List.of(bad, missing, good)).rows();
        assertThat(errors(rows.get(0))).containsExactly("gtin: Invalid check digit; did you mean 4006381333931?");
        assertThat(errors(rows.get(1))).containsExactly("gtin: Required, or answer Yes in \"No barcode\"");
        assertThat(errors(rows.get(2))).isEmpty();
    }

    @Test
    void booksNeedAnIsbn13() {
        List<String> header = List.of("SKU", "Title", "Author", "Image URLs", "Price (USD)", "Quantity", "ISBN-13",
                "Variation", "Format");
        ParsedSheet s = ImportFixtures.sheet(header,
                new String[] {"BK-1", "Jollof Road, a cookbook", "T. Adeyemi", ImportFixtures.IMG, "25", "3",
                    ImportFixtures.VALID_GTIN, "", ""},
                new String[] {"BK-2", "Jollof Road, a cookbook", "T. Adeyemi", ImportFixtures.IMG, "25", "3",
                    "9780141439518", "Format", "Paperback"});
        List<ValidatedRow> rows = ImportValidator.validate(s, ImportFixtures.create(ImportFixtures.BOOK)).rows();
        assertThat(errors(rows.get(0))).containsExactly("gtin: Not a valid ISBN-13 (978/979 prefix, check digit)");
        assertThat(errors(rows.get(1))).isEmpty();
    }

    @Test
    void duplicateSkusInTheFileAndAgainstListingsAreErrors() {
        ImportContext ctx = new ImportContext(ImportMode.CREATE, ImportFixtures.DRESS, ImportFixtures.TYPES,
                List.of(ImportFixtures.DRESSES), Set.of("AWD-LISTED"), Set.of(), Map.of());
        List<ValidatedRow> rows = validate(ctx, List.of(dress("AWD-6"), dress("awd-6"), dress("AWD-LISTED"))).rows();
        assertThat(errors(rows.get(0))).isEmpty();
        assertThat(errors(rows.get(1))).containsExactly("sku: Duplicate SKU: also on row 2");
        assertThat(errors(rows.get(2)))
                .containsExactly("sku: You already list this SKU; upload it in Update mode to change it");
    }

    @Test
    void barcodesThatAlreadyHaveAPageOrRepeatAreErrors() {
        Map<String, String> a = dress("AWD-7");
        a.put("No barcode", "");
        a.put("Barcode (GTIN/UPC/EAN)", ImportFixtures.VALID_GTIN);
        Map<String, String> b = new LinkedHashMap<>(a);
        b.put("SKU *", "AWD-8");
        ImportContext ctx = new ImportContext(ImportMode.CREATE, ImportFixtures.DRESS, ImportFixtures.TYPES,
                List.of(ImportFixtures.DRESSES), Set.of(), Set.of(ImportFixtures.VALID_GTIN), Map.of());
        List<ValidatedRow> rows = validate(ctx, List.of(a, b)).rows();
        assertThat(errors(rows.get(0))).singleElement().asString().contains("already has a product page");
        assertThat(errors(rows.get(1))).contains("gtin: Duplicate barcode: also on row 2");
    }

    @Test
    void variantsShareAParentSkuAndTheirOptionsAreChecked() {
        Map<String, String> m = dress("AWD-M-GLD");
        m.put("Parent SKU", "AWD");
        m.put("Variation", "Size × Color");
        m.put("Size", "M");
        m.put("Color", "gold");
        Map<String, String> l = new LinkedHashMap<>();
        l.put("Parent SKU", "AWD");
        l.put("SKU *", "AWD-L-IND");
        l.put("Price (USD) *", "52");
        l.put("Quantity *", "4");
        l.put("No barcode", "Yes");
        l.put("Size", "L");
        l.put("Color", "Indigo");
        l.put("Title *", "A different title is ignored");
        ValidationResult res = validate(ImportFixtures.create(ImportFixtures.DRESS), List.of(m, l));
        assertThat(res.rows()).allSatisfy(r -> assertThat(errors(r)).isEmpty());
        assertThat(warnings(res.rows().get(1))).contains("title: Ignored: a product's title is taken from row 2");
        assertThat(res.productCount()).isEqualTo(1);
        ImportedProduct p = res.products().getFirst();
        assertThat(p.groupKey()).isEqualTo("AWD");
        assertThat(p.variationTheme()).isEqualTo("Size × Color");
        assertThat(p.variants()).extracting(ImportedVariant::options)
                .containsExactly(Map.of("Size", "M", "Color", "Gold"), Map.of("Size", "L", "Color", "Indigo"));
        assertThat(p.title()).isEqualTo("Ankara Wrap Dress, Puff Sleeve");
    }

    @Test
    void oneBadVariantHoldsBackItsSiblings() {
        Map<String, String> m = dress("AWD-M");
        m.put("Parent SKU", "AWD");
        m.put("Variation", "Size");
        m.put("Size", "M");
        Map<String, String> xl = dress("AWD-XL");
        xl.put("Parent SKU", "AWD");
        xl.put("Size", "XL");
        Map<String, String> dup = dress("AWD-M2");
        dup.put("Parent SKU", "AWD");
        dup.put("Size", "M");
        ValidationResult res = validate(ImportFixtures.create(ImportFixtures.DRESS), List.of(m, xl, dup));
        assertThat(errors(res.rows().get(1))).containsExactly("option:Size: Size: choose one of [S, M, L]");
        assertThat(errors(res.rows().get(2))).contains("option:Size: Same options as another variant of this product");
        assertThat(errors(res.rows().get(0)))
                .containsExactly("parent_sku: Another variant of this product has errors (row 3); variants are imported together");
        assertThat(res.products()).isEmpty();
    }

    @Test
    void severalRowsWithoutAVariationAreAnError() {
        Map<String, String> a = dress("AWD-A");
        a.put("Parent SKU", "AWD");
        Map<String, String> b = dress("AWD-B");
        b.put("Parent SKU", "AWD");
        ValidationResult res = validate(ImportFixtures.create(ImportFixtures.DRESS), List.of(a, b));
        assertThat(errors(res.rows().get(0))).anyMatch(e -> e.startsWith("variation_theme: Set the Variation"));
    }

    @Test
    void numberAttributesTakeAUnitFromItsColumn() {
        List<String> header = List.of("SKU", "Title", "Brand", "Image URLs", "Price (USD)", "Quantity", "No barcode",
                "Category", "Ingredients", "Shelf life", "Shelf life unit", "Pre-order eligible");
        ParsedSheet s = ImportFixtures.sheet(header,
                new String[] {"SUYA-1", "Suya Pepper Spice Blend", "Mama Kano", ImportFixtures.IMG, "8.99", "40", "Yes",
                    "Spices & seasonings", "Groundnut, chilli", "12", "months", "No"},
                new String[] {"SUYA-2", "Suya Pepper Spice Blend", "Mama Kano", ImportFixtures.IMG, "8.99", "40", "Yes",
                    "Groceries › Pantry › Snacks", "Groundnut, chilli", "twelve", "", ""},
                new String[] {"SUYA-3", "Suya Pepper Spice Blend", "Mama Kano", ImportFixtures.IMG, "8.99", "40", "Yes",
                    "", "Groundnut, chilli", "3", "weeks", ""});
        List<ValidatedRow> rows = ImportValidator.validate(s, ImportFixtures.create(ImportFixtures.GROCERY)).rows();
        assertThat(errors(rows.get(0))).isEmpty();
        assertThat(errors(rows.get(1))).containsExactly("attr:shelf: Enter a number and a unit (days, months)");
        assertThat(errors(rows.get(2))).contains("category: Required: choose the category from the list",
                "attr:shelf: Unit must be one of days, months");
        ImportedProduct p = ImportValidator.validate(ImportFixtures.sheet(header, new String[] {"SUYA-1",
            "Suya Pepper Spice Blend", "Mama Kano", ImportFixtures.IMG, "8.99", "40", "Yes", "Spices & seasonings",
            "Groundnut, chilli", "12", "Months", "No"}), ImportFixtures.create(ImportFixtures.GROCERY))
                .products().getFirst();
        assertThat(p.attributes()).containsEntry("shelf", "12 months");
        assertThat(p.preorderEligible()).isFalse();
    }

    @Test
    void fulfilledByAfroTransactNeedsThePackage() {
        Map<String, String> r = dress("AWD-FBA");
        r.put("Fulfilment", "Fulfilled by AfroTransact");
        r.put("Quantity *", "");
        Map<String, String> partial = dress("AWD-FBA2");
        partial.put("Package length (in)", "12");
        Map<String, String> ok = dress("AWD-FBA3");
        ok.put("Fulfilment", "AFROTRANSACT");
        ok.put("Package length (in)", "12");
        ok.put("Package width (in)", "10");
        ok.put("Package height (in)", "2");
        ok.put("Package weight (lb)", "1,5");
        List<ValidatedRow> rows = validate(ImportFixtures.create(ImportFixtures.DRESS), List.of(r, partial, ok)).rows();
        assertThat(errors(rows.get(0))).containsExactly(
                "package_length_in: Package size and weight are required for Fulfilled by AfroTransact");
        assertThat(errors(rows.get(1))).containsExactly(
                "package_width_in: Give the package length, width, height and weight");
        assertThat(errors(rows.get(2))).isEmpty();
        ValidationResult res = validate(ImportFixtures.create(ImportFixtures.DRESS), List.of(ok));
        ImportedProduct p = res.products().getFirst();
        assertThat(p.fulfilment()).isEqualTo(FulfilmentMode.AFROTRANSACT);
        assertThat(p.packaging().weightLb()).isEqualTo(1.5);
    }

    @Test
    void compareAtPriceBelowThePriceIsIgnoredWithAWarning() {
        Map<String, String> r = dress("AWD-CMP");
        r.put("Compare-at price (USD)", "$45");
        r.put("Description", "");
        ValidatedRow row = validate(ImportFixtures.create(ImportFixtures.DRESS), List.of(r)).rows().getFirst();
        assertThat(row.status()).isEqualTo(RowStatus.WARNING);
        assertThat(warnings(row)).contains(
                "compare_at_price: Ignored: the compare-at price must be higher than the price",
                "description: No description: listings with one convert better");
    }

    @Test
    void updateModeChangesListedSkusAndIgnoresStructure() {
        UUID productId = UUID.randomUUID();
        ImportContext ctx = new ImportContext(ImportMode.UPDATE, ImportFixtures.DRESS, ImportFixtures.TYPES,
                List.of(ImportFixtures.DRESSES), Set.of("AWD-M"), Set.of(),
                Map.of("AWD-M", new ExistingListing(productId, "dress")));
        Map<String, String> change = new LinkedHashMap<>();
        change.put("SKU *", "awd-m");
        change.put("Price (USD) *", "39.50");
        change.put("Size", "L");
        Map<String, String> unknown = new LinkedHashMap<>();
        unknown.put("SKU *", "NOPE-1");
        unknown.put("Quantity *", "3");
        ValidationResult res = validate(ctx, List.of(change, unknown));
        assertThat(errors(res.rows().get(0))).isEmpty();
        assertThat(warnings(res.rows().get(0))).containsExactly("option:Size: Ignored in Update mode");
        assertThat(errors(res.rows().get(1)))
                .containsExactly("sku: No listing has this SKU; upload it in Create mode to add it");
        ImportedProduct p = res.products().getFirst();
        assertThat(p.productId()).isEqualTo(productId);
        assertThat(p.title()).isNull();
        assertThat(p.attributes()).isEmpty();
        assertThat(p.imageUrls()).isEmpty();
        ImportedVariant v = p.variants().getFirst();
        assertThat(v.priceMinor()).isEqualTo(3950);
        assertThat(v.quantity()).isNull();
        assertThat(p.condition()).isNull();
    }

    @Test
    void theGenericTemplateReadsCategoryAttributesAndOptionsAsPairs() {
        List<String> header = ImportColumns.generic(ImportFixtures.ALL.stream().map(ImportCategory::path).toList())
                .stream().map(ImportColumn::header).toList();
        Map<String, String> r = new LinkedHashMap<>();
        r.put("Parent SKU", "");
        r.put("SKU *", "GEN-1");
        r.put("Category *", "Fashion › Women › Dresses");
        r.put("Title *", "Ankara Wrap Dress, Puff Sleeve");
        r.put("Brand", "Adire Lagos");
        r.put("Image URLs *", ImportFixtures.IMG);
        r.put("Price (USD) *", "49");
        r.put("Quantity *", "2");
        r.put("No barcode", "yes");
        r.put("Variation", "Size");
        r.put("Options", "Size: M");
        r.put("Attributes", "material: Silk; colourway: Blue");
        r.put("Condition", "Used - like new");
        ParsedSheet s = sheet(header, List.of(r));
        ValidationResult res = ImportValidator.validate(s, ImportFixtures.create(null));
        assertThat(errors(res.rows().getFirst())).isEmpty();
        assertThat(warnings(res.rows().getFirst()))
                .contains("attributes: Ignored attributes not used by Women's dresses: colourway");
        ImportedProduct p = res.products().getFirst();
        assertThat(p.productTypeCode()).isEqualTo("dress");
        assertThat(p.attributes()).containsEntry("material", "Silk");
        assertThat(p.variants().getFirst().options()).containsEntry("Size", "M");
        assertThat(p.condition()).isEqualTo(ItemCondition.USED_LIKE_NEW);
    }

    @Test
    void filesWithoutRequiredColumnsOrRowsOrTooManyRowsAreRefused() {
        ParsedSheet noPrice = ImportFixtures.sheet(List.of("SKU", "Title", "Image URLs", "Quantity"),
                new String[] {"A-1", "Something long enough", ImportFixtures.IMG, "1"});
        assertThatThrownBy(() -> ImportValidator.validate(noPrice, ImportFixtures.create(ImportFixtures.DRESS)))
                .isInstanceOf(SpreadsheetRejected.class).hasMessageContaining("Missing required column: Price (USD) *");
        ParsedSheet empty = new ParsedSheet(DRESS_HEADER, List.of());
        assertThatThrownBy(() -> ImportValidator.validate(empty, ImportFixtures.create(ImportFixtures.DRESS)))
                .hasMessageContaining("no product rows");
        List<Map<String, String>> many = new ArrayList<>();
        for (int i = 0; i <= ImportLimits.MAX_ROWS; i++) {
            many.add(dress("R-" + i));
        }
        assertThatThrownBy(() -> validate(ImportFixtures.create(ImportFixtures.DRESS), many))
                .hasMessageContaining("upload at most 5000 rows");
    }

    @Test
    void unknownColumnsAreReportedOnce() {
        List<String> header = new ArrayList<>(DRESS_HEADER);
        header.add("Supplier");
        Map<String, String> r = dress("AWD-U");
        ValidationResult res = ImportValidator.validate(sheet(header, List.of(r)),
                ImportFixtures.create(ImportFixtures.DRESS));
        assertThat(res.fileWarnings()).containsExactly("Ignored column not in the template: Supplier");
    }

    @Test
    void moneyAndNumberParsing() {
        assertThat(ImportValidator.money("24.99")).isEqualTo(2499);
        assertThat(ImportValidator.money("$1,299")).isEqualTo(129_900);
        assertThat(ImportValidator.money("24,9")).isEqualTo(2490);
        assertThat(ImportValidator.money("24.999")).isNull();
        assertThat(ImportValidator.integer("12.0")).isEqualTo(12);
        assertThat(ImportValidator.integer("1.5")).isNull();
        assertThat(ImportValidator.bool("Y")).isTrue();
        assertThat(ImportValidator.bool("maybe")).isNull();
        assertThat(ImportValidator.urlProblem("https://user:pw@example.com/a.jpg")).isEqualTo("Not a public web address");
    }
}
