package com.afrotransact.catalog.application;

import java.time.Instant;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;

/**
 * Applies an approved content request (title, description, one detail, brand, category, pictures) to the page. It changes
 * what the page says and never its status: putting a page on sale or taking it off stays with the live gate and
 * moderation (checked by {@code TaxGateArchitectureTest}). A change that would alter a tax fact is refused.
 */
public interface ChangeRequestApplier {

    /**
     * Stores the changed page (its version goes up by one) and publishes {@code catalog.product.updated}.
     *
     * @throws com.afrotransact.platform.web.error.BusinessRuleException when the change cannot be applied here
     *         (a tax fact, another tax profile, an unknown brand, a picture the checks refuse)
     */
    Product apply(ProductChangeRequest request, Product page, Instant now);
}
