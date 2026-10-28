package com.afrotransact.catalog.domain.imports;

import java.util.List;

/**
 * The outcome of validating an uploaded sheet.
 *
 * @param fileWarnings notes about the file as a whole (unknown columns ignored …)
 * @param products     the products that can be imported (every row valid or warning)
 */
public record ValidationResult(List<String> header, List<String> fileWarnings, List<ValidatedRow> rows,
                               List<ImportedProduct> products, int productCount) {

    public ValidationResult {
        header = List.copyOf(header);
        fileWarnings = List.copyOf(fileWarnings);
        rows = List.copyOf(rows);
        products = List.copyOf(products);
    }

    public long count(RowStatus status) {
        return rows.stream().filter(r -> r.status() == status).count();
    }
}
