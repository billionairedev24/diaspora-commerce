package com.afrotransact.catalog.application;

/** Event types published by the catalog service (documented in contracts/asyncapi/events.yaml). */
public final class CatalogEvents {

    public static final String AGGREGATE_PRODUCT = "product";
    public static final String AGGREGATE_CATEGORY_REQUEST = "category-request";

    public static final String PRODUCT_CREATED = "catalog.product.created";
    public static final String PRODUCT_APPROVED = "catalog.product.approved";
    public static final String PRODUCT_REJECTED = "catalog.product.rejected";
    public static final String PRODUCT_PAUSED = "catalog.product.paused";
    public static final String PRODUCT_RESUMED = "catalog.product.resumed";
    /** The owner's listings were hidden by seller-service compliance enforcement (payload sellerHidden=true). */
    public static final String PRODUCT_HIDDEN = "catalog.product.hidden";
    /** The owner's listings were restored (payload sellerHidden=false, status unchanged). */
    public static final String PRODUCT_RESTORED = "catalog.product.restored";
    public static final String CATEGORY_REQUESTED = "catalog.category-request.created";
    public static final String CATEGORY_APPROVED = "catalog.category-request.approved";
    public static final String CATEGORY_REJECTED = "catalog.category-request.rejected";

    private CatalogEvents() {
    }
}
