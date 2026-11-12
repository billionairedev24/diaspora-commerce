package com.afrotransact.catalog.domain.imports;

import java.util.List;

/** Validation of a price and quantity feed: the stored rows and the typed rows without errors. */
public record PriceQtyResult(ValidationResult validation, List<PriceQtyRow> importable) {

    public PriceQtyResult {
        importable = List.copyOf(importable);
    }
}
