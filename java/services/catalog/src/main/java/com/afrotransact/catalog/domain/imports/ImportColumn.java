package com.afrotransact.catalog.domain.imports;

import java.util.List;

/**
 * A column of the bulk upload template.
 *
 * @param key         stable key (also accepted as the header text): {@code sku}, {@code attr:material}
 * @param label       header text in the template; a trailing {@code *} marks required columns
 * @param description what goes in the column (header note and Instructions sheet)
 * @param required    must be filled (on the first row of a product for product-level columns) when creating
 * @param options     allowed values for {@link ColumnKind#ENUM} columns (dropdowns)
 * @param example     value of the example row
 * @param productLevel the same for every variant of a product (read from the product's first row)
 */
public record ImportColumn(String key, String label, String description, boolean required, ColumnKind kind,
                           List<String> options, String example, boolean productLevel) {

    public ImportColumn {
        options = options == null ? List.of() : List.copyOf(options);
        example = example == null ? "" : example;
    }

    /** The header text written in templates. */
    public String header() {
        return required ? label + " *" : label;
    }
}
