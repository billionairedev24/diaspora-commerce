package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.testsupport.RulesFixtures;
import org.junit.jupiter.api.Test;

/** The bulk upload follows the same listing rules as the form and the API: columns, required marks and row errors. */
class ImportListingRulesTest {

    private static final ProductType PLAIN = new ProductType("t", "Test type", List.of(), List.of(), IdentifierRule.GTIN);
    private static final ProductType SIZED = new ProductType("t", "Test type", List.of(), List.of(new VariationTheme(
            "Size", List.of(new VariationDimension("Size", List.of("S", "M"))))), IdentifierRule.GTIN);
    private static final ProductType BOOKS = new ProductType("b", "Books", List.of(), List.of(), IdentifierRule.ISBN);

    private static ImportCategory category(String code) {
        return new ImportCategory(UUID.randomUUID(), "Dept › " + code + " › " + code, "t", "ACTIVE",
                RulesFixtures.book().forCategory(code).orElseThrow());
    }

    private static List<ImportColumn> columns(ProductType type, String code) {
        return ImportColumns.forCategories(type, List.of(category(code)));
    }

    private static List<String> keys(List<ImportColumn> cols) {
        return cols.stream().map(ImportColumn::key).toList();
    }

    private static ImportColumn column(List<ImportColumn> cols, String key) {
        return cols.stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ template

    @Test
    void packagedGroceriesHaveALabelPhotoColumnAndNoTypedIngredientsOrCondition() {
        List<ImportColumn> cols = columns(PLAIN, "beans-pantry-sauces");

        assertThat(keys(cols)).contains("label_photo_url", "attr:net_weight", "attr:net_weight:unit", "attr:servings",
                "attr:shelf", "attr:origin", "attr:storage", "attr:allergens", "brand")
                .doesNotContain("attr:ingredients", "condition", "dangerous_goods", "attr:material");
        assertThat(column(cols, "label_photo_url").required()).isTrue();
        assertThat(column(cols, "attr:net_weight").required()).isTrue();
        assertThat(column(cols, "attr:storage").required()).isFalse();
        assertThat(column(cols, "attr:allergens").description()).contains("Contains milk").contains("None");
        assertThat(column(cols, "label_photo_url").header()).endsWith("*");
    }

    @Test
    void foodServiceHasTypedIngredientsAndAllergensButNoBrandOriginOrBarcode() {
        List<ImportColumn> cols = columns(PLAIN, "prepared-meals");

        assertThat(keys(cols)).contains("attr:ingredients", "attr:allergens", "attr:storage")
                .doesNotContain("brand", "attr:origin", "gtin", "gtin_exempt", "dangerous_goods");
        assertThat(column(cols, "attr:ingredients").required()).isTrue();
        assertThat(column(cols, "attr:allergens").required()).isTrue();
        assertThat(column(cols, "label_photo_url").required()).isFalse();
    }

    @Test
    void electronicsShowConditionAndRequireTheDangerousGoodsAnswerAndModel() {
        List<ImportColumn> cols = columns(PLAIN, "phones");

        assertThat(keys(cols)).contains("condition", "dangerous_goods", "attr:model");
        assertThat(column(cols, "dangerous_goods").required()).isTrue();
        assertThat(column(cols, "attr:model").required()).isTrue();
        assertThat(column(cols, "gtin").required()).isTrue();
        assertThat(keys(cols)).doesNotContain("gtin_exempt", "label_photo_url");
    }

    @Test
    void digitalProductsHaveNoPackageQuantityOrBarcodeColumns() {
        List<ImportColumn> cols = columns(PLAIN, "downloadable-ebooks");

        assertThat(keys(cols)).doesNotContain("package_length_in", "package_weight_lb", "quantity", "gtin", "handling_days",
                "attr:origin", "condition");
        assertThat(keys(cols)).contains("brand", "description", "price");
    }

    @Test
    void thePackageColumnsArePerRowAndRequiredForPhysicalProducts() {
        List<ImportColumn> cols = columns(PLAIN, "sports-goods");

        for (String key : List.of("package_length_in", "package_width_in", "package_height_in", "package_weight_lb")) {
            assertThat(column(cols, key).productLevel()).as(key).isFalse();
        }
        assertThat(column(cols, "package_length_in").required()).isTrue();
        assertThat(column(cols, "package_length_in").description()).contains("first row").contains("108");
    }

    @Test
    void aTemplateOfSeveralCategoriesShowsAColumnWhenAnyAsksAndRequiresItWhenAllDo() {
        List<ImportColumn> cols = ImportColumns.forCategories(PLAIN, List.of(category("beans-pantry-sauces"),
                category("prepared-meals")));

        assertThat(keys(cols)).contains("attr:ingredients", "attr:origin", "brand");
        assertThat(column(cols, "attr:ingredients").required()).isFalse();
        assertThat(column(cols, "attr:net_weight").required()).isTrue();
        assertThat(column(cols, "label_photo_url").required()).isFalse();
    }

    @Test
    void theTemplateCarriesTheExemptionColumnOnlyWhereARuleAllowsAnExemption() {
        assertThat(keys(columns(PLAIN, "bedding-linens"))).contains("why_not_provided");
        assertThat(keys(columns(PLAIN, "beans-pantry-sauces"))).doesNotContain("why_not_provided");
        assertThat(keys(columns(PLAIN, "downloadable-ebooks"))).doesNotContain("why_not_provided");
    }

    // ------------------------------------------------------------------ rows

    private static ValidationResult run(ProductType type, String code, Function<String, String> override,
                                        int rows) {
        ImportCategory cat = category(code);
        List<ImportColumn> cols = ImportColumns.forCategories(type, List.of(cat));
        List<String> header = cols.stream().map(ImportColumn::header).toList();
        List<SheetRow> sheet = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            List<String> cells = new ArrayList<>();
            for (ImportColumn c : cols) {
                String v = override.apply(c.key() + "#" + i);
                if (v == null) {
                    v = override.apply(c.key());
                }
                cells.add(v == null ? defaultCell(c.key(), i) : v);
            }
            sheet.add(new SheetRow(i + 2, cells));
        }
        ImportContext ctx = new ImportContext(ImportMode.CREATE, type, Map.of(type.code(), type), List.of(cat), Set.of(),
                Set.of(), Map.of());
        return ImportValidator.validate(new ParsedSheet(header, sheet), ctx);
    }

    private static String defaultCell(String key, int row) {
        return switch (key) {
            case "parent_sku" -> "P-1";
            case "sku" -> "SKU-" + row;
            case "title" -> "Plantain chips, 8 oz bag";
            case "description" -> "Crunchy plantain chips";
            case "brand" -> "Mama Kano";
            case "image_urls" -> ImportFixtures.IMG;
            case "dangerous_goods" -> "No";
            case "attr:material" -> "Cotton";
            case "attr:model" -> "M-1";
            case "attr:age_grade" -> "3+ years";
            case "attr:storage" -> "Room temperature";
            case "attr:allergens" -> "None";
            case "attr:ingredients" -> "Plantain, palm oil, salt";
            case "price" -> "6.50";
            case "quantity" -> "20";
            case "gtin_exempt" -> "Yes";
            case "label_photo_url" -> "https://cdn.example.com/label.jpg";
            case "attr:net_weight" -> "8";
            case "attr:net_weight:unit" -> "oz";
            case "attr:servings" -> "4";
            case "attr:shelf" -> "12";
            case "attr:shelf:unit" -> "months";
            case "attr:origin" -> "Nigeria";
            case "package_length_in" -> row == 0 ? "10" : "";
            case "package_width_in" -> row == 0 ? "8" : "";
            case "package_height_in" -> row == 0 ? "4" : "";
            case "package_weight_lb" -> row == 0 ? "1.5" : "";
            case "variation_theme" -> row == 0 ? "Size" : "";
            case "option:Size" -> row == 0 ? "S" : "M";
            default -> "";
        };
    }

    private static List<String> errors(ValidationResult r, int row) {
        return r.rows().get(row).messages().stream().filter(m -> m.severity() == Severity.ERROR)
                .map(m -> m.column() + ": " + m.message()).toList();
    }

    private static List<String> warnings(ValidationResult r, int row) {
        return r.rows().get(row).messages().stream().filter(m -> m.severity() == Severity.WARNING)
                .map(m -> m.column() + ": " + m.message()).toList();
    }

    @Test
    void aCompletePackagedGroceryRowIsAccepted() {
        ValidationResult r = run(PLAIN, "packaged-snacks", k -> null, 1);

        assertThat(errors(r, 0)).isEmpty();
        assertThat(r.products()).singleElement().satisfies(p -> {
            assertThat(p.labelPhotoUrl()).isEqualTo("https://cdn.example.com/label.jpg");
            assertThat(p.packaging().weightLb()).isEqualTo(1.5);
        });
    }

    @Test
    void aMissingLabelPhotoIsAPlainErrorOnItsColumn() {
        ValidationResult r = run(PLAIN, "packaged-snacks", k -> k.equals("label_photo_url") ? "" : null, 1);

        assertThat(errors(r, 0)).containsExactly("label_photo_url: Add a photo of the label: the ingredients and nutrition panel");
    }

    @Test
    void aMissingNetWeightServingsAndShelfLifeAreErrorsOnTheirColumns() {
        ValidationResult r = run(PLAIN, "packaged-snacks", k -> k.equals("attr:net_weight") || k.equals("attr:servings")
                || k.equals("attr:shelf") ? "" : null, 1);

        assertThat(errors(r, 0)).contains("attr:net_weight: Required", "attr:servings: Required", "attr:shelf: Required");
    }

    @Test
    void theSecondVariantStartsFromTheFirstRowsPackage() {
        ValidationResult r = run(SIZED, "packaged-snacks", k -> null, 2);

        assertThat(errors(r, 0)).isEmpty();
        assertThat(errors(r, 1)).isEmpty();
        assertThat(r.products()).singleElement().satisfies(p -> {
            assertThat(p.variants().get(0).packaging().weightLb()).isEqualTo(1.5);
            assertThat(p.variants().get(1).packaging()).isNull();
            assertThat(p.packaging().lengthIn()).isEqualTo(10);
        });
    }

    @Test
    void aVariantMayCarryItsOwnPackage() {
        ValidationResult r = run(SIZED, "packaged-snacks", k -> switch (k) {
            case "package_length_in#1" -> "20";
            case "package_width_in#1" -> "12";
            case "package_height_in#1" -> "6";
            case "package_weight_lb#1" -> "3";
            default -> null;
        }, 2);

        assertThat(errors(r, 1)).isEmpty();
        assertThat(r.products().getFirst().variants().get(1).packaging().weightLb()).isEqualTo(3);
    }

    @Test
    void noPackageAtAllOnAPhysicalProductIsAnErrorOnTheLengthColumnOfThatRow() {
        ValidationResult r = run(PLAIN, "packaged-snacks", k -> k.startsWith("package_") ? "" : null, 1);

        assertThat(errors(r, 0)).containsExactly("package_length_in: Add the package length, width, height and weight of the box as shipped");
    }

    @Test
    void aPackageOverTheLimitsOrWithMissingPartsIsRefusedInPlainWords() {
        assertThat(errors(run(PLAIN, "packaged-snacks", k -> k.equals("package_length_in") ? "109" : null, 1), 0))
                .containsExactly("package_length_in: Each side must be at most 108 in");
        assertThat(errors(run(PLAIN, "packaged-snacks", k -> k.equals("package_weight_lb") ? "" : null, 1), 0))
                .containsExactly("package_weight_lb: Give the package length, width, height and weight");
    }

    @Test
    void aCellOfAHiddenQuestionIsIgnoredWithANoteAndNeverBlocks() {
        ValidationResult r = run(PLAIN, "prepared-meals", k -> k.equals("attr:origin") ? "Nigeria" : null, 1);

        assertThat(errors(r, 0)).isEmpty();
    }

    @Test
    void aBarcodeIsRequiredWhereTheMatrixSaysRequiredAndAnExemptionIsNotAccepted() {
        ValidationResult r = run(PLAIN, "phones", k -> switch (k) {
            case "gtin" -> "";
            case "dangerous_goods" -> "No";
            case "attr:model" -> "X1";
            case "attr:origin" -> "China";
            default -> null;
        }, 1);

        assertThat(errors(r, 0)).containsExactly("gtin: Required: enter the barcode");
    }

    @Test
    void theDangerousGoodsAnswerIsRequiredWhereAskedAndYesIsCarriedToTheProduct() {
        ValidationResult missing = run(PLAIN, "decor-candles", k -> switch (k) {
            case "dangerous_goods" -> "";
            case "attr:material" -> "Wax";
            case "attr:model" -> "";
            case "why_not_provided" -> "Model number: handmade candle";
            default -> null;
        }, 1);
        assertThat(errors(missing, 0)).containsExactly("dangerous_goods: Answer yes or no: is it an aerosol, flammable, battery, candle or cleaner?");

        ValidationResult yes = run(PLAIN, "decor-candles", k -> switch (k) {
            case "gtin_exempt" -> "Yes";
            case "dangerous_goods" -> "Yes";
            case "attr:material" -> "Wax";
            case "attr:model" -> "";
            case "why_not_provided" -> "Model number: handmade candle";
            default -> null;
        }, 1);
        assertThat(errors(yes, 0)).isEmpty();
        assertThat(yes.products().getFirst().dangerousGoods()).isTrue();
        assertThat(yes.products().getFirst().exemptions()).containsEntry("model_number", "handmade candle");
    }

    @Test
    void anExemptionNeedsAReasonAndOnlyWorksWhereTheRuleAllowsIt() {
        ValidationResult noReason = run(PLAIN, "bedding-linens", k -> switch (k) {
            case "dangerous_goods" -> "No";
            case "attr:material", "attr:model" -> "";
            case "why_not_provided" -> "Material";
            default -> null;
        }, 1);
        assertThat(errors(noReason, 0)).contains("why_not_provided: Say why Material does not apply");

        ValidationResult notAllowed = run(PLAIN, "bedding-linens", k -> k.equals("why_not_provided") ? "Country of origin: unknown" : null, 1);
        assertThat(warnings(notAllowed, 0)).anyMatch(w -> w.contains("cannot be left out"));
    }

    @Test
    void aBookNeedsTheIsbnAndTheDescription() {
        ValidationResult r = run(BOOKS, "printed-books", k -> switch (k) {
            case "gtin" -> "123";
            case "description" -> "";
            case "attr:origin" -> "Nigeria";
            default -> null;
        }, 1);

        assertThat(errors(r, 0)).contains("gtin: ISBN-13 required (978/979 prefix, valid check digit)",
                "description: Describe the product for shoppers");
    }

    @Test
    void conditionOfAHiddenCategoryIsIgnored() {
        ValidationResult r = run(PLAIN, "packaged-snacks", k -> k.equals("condition") ? "Used - good" : null, 1);

        assertThat(errors(r, 0)).isEmpty();
        assertThat(r.products().getFirst().condition()).isNull();
    }
}
