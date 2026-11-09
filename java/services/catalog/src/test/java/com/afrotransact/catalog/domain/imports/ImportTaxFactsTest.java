package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.afrotransact.catalog.adapter.out.taxonomy.ClasspathTaxonomySource;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner;
import org.junit.jupiter.api.Test;

/** Bulk upload: a column per tax question of the category, row-level messages in plain words, optional SKU. */
class ImportTaxFactsTest {

    private static final ClasspathTaxonomySource SOURCE = new ClasspathTaxonomySource();
    private static final TaxonomyPlanner.Plan PLAN = TaxonomyPlanner.plan(SOURCE.bundled(), SOURCE.schemas());
    private static final ProductType SNACKS = PLAN.types().stream().filter(t -> t.code().equals("packaged-snacks"))
            .findFirst().orElseThrow();
    private static final ProductType BAKERY = PLAN.types().stream().filter(t -> t.code().equals("bakery-products"))
            .findFirst().orElseThrow();
    private static final ImportCategory SNACK_CAT = new ImportCategory(UUID.randomUUID(),
            "Groceries & Food › Packaged snacks › Packaged snacks", "packaged-snacks", "ACTIVE");
    private static final ImportCategory BUNDLE_CAT = new ImportCategory(UUID.randomUUID(),
            "Other & Specialty › Gift baskets / bundles › Gift baskets / bundles", "packaged-snacks", "BLOCKED_BUNDLE");

    private static ImportContext ctx(ProductType type, ImportCategory cat) {
        return new ImportContext(ImportMode.CREATE, type, Map.of(type.code(), type), List.of(cat), Set.of(), Set.of(),
                Map.of());
    }

    private static ValidationResult run(ProductType type, ImportCategory cat, Function<String, String> cell) {
        List<ImportColumn> cols = ImportColumns.forType(type, List.of(cat.path()));
        List<String> header = cols.stream().map(ImportColumn::header).toList();
        List<String> cells = new ArrayList<>();
        for (ImportColumn c : cols) {
            String v = cell.apply(c.key());
            if (v == null) {
                v = switch (c.key()) {
                    case "title" -> "Plantain chips, 8 oz bag";
                    case "image_urls" -> ImportFixtures.IMG;
                    case "price" -> "6.50";
                    case "quantity" -> "20";
                    case "gtin_exempt" -> "Yes";
                    case "attr:ingredients" -> "Plantain, palm oil, salt";
                    case "attr:allergens" -> "None";
                    default -> "";
                };
            }
            cells.add(v);
        }
        return ImportValidator.validate(new ParsedSheet(header, List.of(new SheetRow(2, cells))), ctx(type, cat));
    }

    private static List<String> errors(ValidationResult r) {
        return r.rows().getFirst().messages().stream().filter(m -> m.severity() == Severity.ERROR)
                .map(m -> m.column() + ": " + m.message()).toList();
    }

    @Test
    void theTemplateHasAColumnPerSellerAnsweredQuestionAndNoProofFileColumn() {
        List<String> keys = ImportColumns.forType(SNACKS, List.of(SNACK_CAT.path())).stream().map(ImportColumn::key).toList();
        assertThat(keys).contains("fact:is_candy", "fact:net_weight_oz", "fact:servings_per_package",
                "fact:individual_serving");
        ProductType skin = PLAN.types().stream().filter(t -> t.code().equals("medicated-skincare")).findFirst().orElseThrow();
        assertThat(ImportColumns.forType(skin, List.of("x")).stream().map(ImportColumn::key))
                .contains("fact:label_type", "fact:qualifying_label").doesNotContain("fact:label_evidence");
        ImportColumn weight = ImportColumns.forType(SNACKS, List.of(SNACK_CAT.path())).stream()
                .filter(c -> c.key().equals("fact:net_weight_oz")).findFirst().orElseThrow();
        assertThat(weight.description()).contains("A number in oz.");
        assertThat(weight.kind()).isEqualTo(ColumnKind.DECIMAL);
    }

    @Test
    void theSkuColumnIsOptionalAndAnAtinColumnExists() {
        List<ImportColumn> cols = ImportColumns.forType(SNACKS, List.of(SNACK_CAT.path()));
        assertThat(cols.stream().filter(c -> c.key().equals("sku")).findFirst().orElseThrow().required()).isFalse();
        assertThat(cols).anyMatch(c -> c.key().equals("atin"));
    }

    @Test
    void aRowWithItsFactsAndNoSkuIsValidAndCarriesTheFacts() {
        ValidationResult r = run(SNACKS, SNACK_CAT, k -> switch (k) {
            case "fact:is_candy" -> "No";
            case "fact:net_weight_oz" -> "8.5";
            case "fact:servings_per_package" -> "4";
            case "fact:individual_serving" -> "No";
            default -> null;
        });
        assertThat(errors(r)).isEmpty();
        ImportedVariant v = r.products().getFirst().variants().getFirst();
        assertThat(v.sku()).isEmpty();
        assertThat(v.taxFacts()).containsKeys("is_candy", "net_weight_oz", "servings_per_package", "individual_serving");
        assertThat(((Number) v.taxFacts().get("servings_per_package")).intValue()).isEqualTo(4);
    }

    @Test
    void missingAndWrongFactsAreRowErrorsOnTheirColumnInPlainWords() {
        ValidationResult r = run(SNACKS, SNACK_CAT, k -> switch (k) {
            case "fact:is_candy" -> "No";
            case "fact:servings_per_package" -> "2.5";
            default -> null;
        });
        assertThat(errors(r)).anyMatch(e -> e.startsWith("fact:net_weight_oz: Net weight (oz) is needed."))
                .anyMatch(e -> e.startsWith("fact:servings_per_package:") && e.contains("whole number"))
                .anyMatch(e -> e.startsWith("fact:individual_serving: "));
        assertThat(r.products()).isEmpty();
    }

    @Test
    void aCandyRowNeedsNoWeightAndServings() {
        ValidationResult r = run(SNACKS, SNACK_CAT, k -> "fact:is_candy".equals(k) ? "Yes" : null);
        assertThat(errors(r)).isEmpty();
    }

    @Test
    void behaviourAnswersComeFromTheFirstRowOfTheProduct() {
        ValidationResult r = run(BAKERY, new ImportCategory(UUID.randomUUID(), "Groceries & Food › Bakery products › Bakery products",
                "bakery-products", "ACTIVE"), k -> switch (k) {
                    case "fact:sold_heated" -> "No";
                    case "fact:utensils_provided" -> "No";
                    default -> null;
                });
        assertThat(errors(r)).isEmpty();
        assertThat(r.products().getFirst().sellerBehaviour()).containsEntry("sold_heated", false)
                .containsEntry("utensils_provided", false);
    }

    @Test
    void aClosedCategoryRefusesTheRowWithItsPlainMessage() {
        ValidationResult r = run(SNACKS, BUNDLE_CAT, k -> null);
        assertThat(errors(r)).anyMatch(e -> e.startsWith("category: Gift baskets and bundles cannot be published yet"));
    }
}
