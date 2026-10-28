package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;

/** Product types and categories shaped like the seed taxonomy, for import tests. */
final class ImportFixtures {

    static final ProductType DRESS = new ProductType("dress", "Women's dresses", List.of(
            new AttributeDefinition("brand", "Brand", List.of(), true, false),
            new AttributeDefinition("material", "Material", List.of("Cotton wax print", "Lace", "Silk"), true, false),
            new AttributeDefinition("care", "Care", List.of(), false, false)),
            List.of(new VariationTheme("Size × Color", List.of(
                            new VariationDimension("Size", List.of("S", "M", "L")),
                            new VariationDimension("Color", List.of("Gold", "Indigo")))),
                    new VariationTheme("Size", List.of(new VariationDimension("Size", List.of("S", "M", "L"))))),
            IdentifierRule.GTIN);

    static final ProductType GROCERY = new ProductType("grocery", "Packaged food", List.of(
            new AttributeDefinition("brand", "Brand", List.of(), true, false),
            new AttributeDefinition("ingredients", "Ingredients", List.of(), true, true, null, null,
                    AttributeSection.FOOD, null),
            new AttributeDefinition("shelf", "Shelf life", List.of(), true, false, AttributeKind.NUMBER,
                    List.of("days", "months"), AttributeSection.FOOD, null)),
            List.of(new VariationTheme("Net weight", List.of(new VariationDimension("Net weight",
                    List.of("8 oz", "1 lb"))))),
            IdentifierRule.GTIN, new ProductTypeTraits(true, false, false, true));

    static final ProductType BOOK = new ProductType("book", "Books", List.of(
            new AttributeDefinition("author", "Author", List.of(), true, false)),
            List.of(new VariationTheme("Format", List.of(new VariationDimension("Format",
                    List.of("Hardcover", "Paperback"))))),
            IdentifierRule.ISBN);

    static final ImportCategory DRESSES = new ImportCategory(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Fashion › Women › Dresses", "dress");
    static final ImportCategory SPICES = new ImportCategory(UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "Groceries › Pantry › Spices & seasonings", "grocery");
    static final ImportCategory SNACKS = new ImportCategory(UUID.fromString("00000000-0000-0000-0000-000000000003"),
            "Groceries › Pantry › Snacks", "grocery");
    static final ImportCategory COOKBOOKS = new ImportCategory(UUID.fromString("00000000-0000-0000-0000-000000000004"),
            "Books › Books › Cookbooks", "book");

    static final Map<String, ProductType> TYPES = Map.of("dress", DRESS, "grocery", GROCERY, "book", BOOK);
    static final List<ImportCategory> ALL = List.of(DRESSES, SPICES, SNACKS, COOKBOOKS);

    static final String VALID_GTIN = "4006381333931";
    static final String IMG = "https://cdn.example.com/a.jpg";

    private ImportFixtures() {
    }

    static ImportContext create(ProductType type) {
        return new ImportContext(ImportMode.CREATE, type, TYPES, type == null ? ALL
                : ALL.stream().filter(c -> c.productTypeCode().equals(type.code())).toList(), Set.of(), Set.of(),
                Map.of());
    }

    /** A sheet whose first row is the header; cells by position. */
    static ParsedSheet sheet(List<String> header, String[]... rows) {
        List<SheetRow> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            out.add(new SheetRow(i + 2, Arrays.asList(rows[i])));
        }
        return new ParsedSheet(header, out);
    }
}
