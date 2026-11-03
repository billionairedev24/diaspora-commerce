package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import org.junit.jupiter.api.Test;

/** The brand column of a bulk upload follows the product type's brand attribute, like the product form. */
class ImportBrandTest {

    private static final AttributeDefinition BRAND = new AttributeDefinition("brand", "Brand or label",
            List.of("UNBRANDED", "HANDMADE"), true, false, AttributeKind.BRAND, List.of(), AttributeSection.DETAILS,
            "The label on the garment.", true);
    private static final AttributeDefinition STRICT = new AttributeDefinition("brand", "Brand", List.of(), true,
            false, AttributeKind.BRAND, List.of(), AttributeSection.DETAILS, null, true);
    private static final ProductType FASHION = type("dress", BRAND);
    private static final ProductType BEAUTY = type("beauty", STRICT);
    private static final ImportCategory FASHION_CAT = new ImportCategory(UUID.randomUUID(), "Fashion › Women › Dresses",
            "dress");
    private static final ImportCategory BEAUTY_CAT = new ImportCategory(UUID.randomUUID(), "Beauty › Skin care › Soaps",
            "beauty");
    private static final ImportCategory BOOK_CAT = new ImportCategory(UUID.randomUUID(), "Books › Books › Fiction",
            "book");
    private static final Map<String, ProductType> TYPES = Map.of("dress", FASHION, "beauty", BEAUTY,
            "book", ImportFixtures.BOOK);

    private static ProductType type(String code, AttributeDefinition brand) {
        return new ProductType(code, code, List.of(brand), List.of(), IdentifierRule.GTIN);
    }

    private static ImportContext ctx(ProductType fixed, ImportCategory cat) {
        return new ImportContext(ImportMode.CREATE, fixed, TYPES, List.of(cat), Set.of(), Set.of(), Map.of());
    }

    private static ValidationResult result(ProductType t, ImportCategory cat, String brandHeader, String brand) {
        List<String> header = ImportColumns.forType(t, List.of(cat.path())).stream().map(ImportColumn::header).toList();
        List<String> cells = new ArrayList<>();
        for (String h : header) {
            cells.add(switch (h) {
                case "SKU *" -> "SKU-1";
                case "Title *" -> "Ankara wrap dress, puff sleeve";
                case "Image URLs *" -> ImportFixtures.IMG;
                case "Price (USD) *" -> "49.00";
                case "Quantity *" -> "12";
                case "No barcode" -> "Yes";
                default -> h.equals(brandHeader) ? brand : "";
            });
        }
        ParsedSheet sheet = new ParsedSheet(header, List.of(new SheetRow(2, cells)));
        return ImportValidator.validate(sheet, ctx(t, cat));
    }

    private static ValidatedRow row(ProductType t, ImportCategory cat, String brandHeader, String brand) {
        return result(t, cat, brandHeader, brand).rows().getFirst();
    }

    private static List<String> messages(ValidatedRow r, Severity severity) {
        return r.messages().stream().filter(m -> m.severity() == severity).map(m -> m.column() + ": " + m.message())
                .toList();
    }

    @Test
    void theTemplateColumnUsesTheTypesLabelAndHelp() {
        ImportColumn column = ImportColumns.forType(FASHION, List.of(FASHION_CAT.path())).stream()
                .filter(c -> c.key().equals("brand")).findFirst().orElseThrow();
        assertThat(column.header()).isEqualTo("Brand or label *");
        assertThat(column.description()).contains("The label on the garment.", "No brand", "reviewed by AfroTransact");
        assertThat(ImportColumns.forType(ImportFixtures.BOOK, List.of(BOOK_CAT.path())))
                .noneMatch(c -> c.key().equals("brand"));
    }

    @Test
    void aNamedBrandIsKeptForTheWriterToMatchToTheRegistry() {
        ValidationResult r = result(FASHION, FASHION_CAT, "Brand or label *", " NIKE ");
        assertThat(messages(r.rows().getFirst(), Severity.ERROR)).isEmpty();
        assertThat(r.products().getFirst().attributes()).containsEntry("brand", "NIKE").doesNotContainKey("brand_reason");
    }

    @Test
    void noBrandWordsBecomeAReasonOnlyWhereTheTypeAllowsThem() {
        List<String> header = ImportColumns.forType(FASHION, List.of(FASHION_CAT.path())).stream()
                .map(ImportColumn::header).toList();
        assertThat(header).contains("Brand or label *");
        ValidationResult handmade = result(FASHION, FASHION_CAT, "Brand or label *", "Handmade");
        assertThat(messages(handmade.rows().getFirst(), Severity.ERROR)).isEmpty();
        assertThat(handmade.products().getFirst().attributes()).containsEntry("brand_reason", "HANDMADE")
                .doesNotContainKey("brand");
        ValidationResult none = result(FASHION, FASHION_CAT, "Brand or label *", "No brand");
        assertThat(none.products().getFirst().attributes()).containsEntry("brand_reason", "UNBRANDED");
        assertThat(messages(row(BEAUTY, BEAUTY_CAT, "Brand *", "Generic"), Severity.ERROR))
                .containsExactly("brand: Name the brand; this category does not accept items without one");
    }

    @Test
    void aRequiredBrandCannotBeLeftEmpty() {
        assertThat(messages(row(FASHION, FASHION_CAT, "Brand or label *", ""), Severity.ERROR))
                .containsExactly("brand: Required. Write \"No brand\" or \"Handmade\" if it has none");
        assertThat(messages(row(BEAUTY, BEAUTY_CAT, "Brand *", ""), Severity.ERROR)).containsExactly("brand: Required");
    }
}
