package com.afrotransact.catalog.domain;

import com.afrotransact.catalog.domain.rules.CategoryRules;

/** Validates a product draft against its product type before it can be submitted. */
public interface ProductDraftPolicy {

    int MAX_IMAGES = 10;
    int MIN_TITLE = 10;
    int MAX_TITLE = 200;
    int MAX_QUANTITY = 100_000;
    int MAX_VARIANTS = 200;

    /**
     * Throws {@link CatalogRuleViolation} listing every failing field. Without rules only the structural checks run
     * (title, attributes, images, variants, limits); {@link #validate(ProductDraft, CategoryRules)} is what the product
     * writer calls.
     */
    default void validate(ProductDraft draft) {
        validate(draft, null);
    }

    /**
     * As {@link #validate(ProductDraft)} and also applies the listing rules of the category: which fields are
     * required, optional or hidden. The draft's product type is the type as the rules see it
     * ({@code RuleAwareType.apply}).
     */
    void validate(ProductDraft draft, CategoryRules rules);
}
