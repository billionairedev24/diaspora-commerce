package com.afrotransact.catalog.domain;

/** Validates a product draft against its product type before it can be submitted. */
public interface ProductDraftPolicy {

    int MAX_IMAGES = 10;
    int MIN_TITLE = 10;
    int MAX_TITLE = 200;
    int MAX_QUANTITY = 100_000;
    int MAX_VARIANTS = 200;

    /** Throws {@link CatalogRuleViolation} listing every failing field. */
    void validate(ProductDraft draft);
}
