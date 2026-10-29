package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;

/**
 * Writes products for an owner that is already authorised: the one-step form (through {@link ProductService}) and
 * the bulk-upload job (which runs without a request and acts for the organisation that uploaded the file). Both use
 * the same placement, policy, duplicate checks and events, so a bulk upload can never create a product the form
 * would refuse.
 */
interface ProductWriter {

    /** Validates and creates the product (IN_REVIEW, or PENDING_CATEGORY_APPROVAL), publishing catalog.product.created. */
    Product create(ListingOwner owner, CreateProductCommand command);

    /**
     * Applies an edit: validates the whole product again, matches variants by SKU and publishes
     * catalog.product.updated, plus catalog.product.terms_changed when prices, quantities or listing terms changed and
     * catalog.product.resubmitted when a rejected listing goes back to review.
     */
    Product update(Product current, UpdateProductCommand command);
}
