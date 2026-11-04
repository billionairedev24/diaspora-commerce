package com.afrotransact.catalog.domain.brand;

/**
 * The product attribute keys that carry the brand. {@link #NAME} holds the display name every consumer already reads
 * (search facet, storefront); {@link #ID} links it to the registry; {@link #REASON} says why there is no brand.
 */
public final class BrandKeys {

    public static final String NAME = "brand";
    public static final String ID = "brand_id";
    public static final String REASON = "brand_reason";

    private BrandKeys() {
    }
}
