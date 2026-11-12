package com.afrotransact.catalog.domain.imports;

import java.util.List;

/**
 * The outcome of validating an offer upload: the stored rows with their messages, and the typed rows that can be sent.
 *
 * @param importable every row without an error, in file order
 */
public record OfferImportResult(ValidationResult validation, List<OfferRow> importable) {

    public OfferImportResult {
        importable = List.copyOf(importable);
    }
}
