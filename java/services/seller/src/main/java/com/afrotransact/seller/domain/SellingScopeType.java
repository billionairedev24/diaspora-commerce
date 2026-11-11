package com.afrotransact.seller.domain;

/**
 * What a selling rule or approval is about (docs/engineering/listing-rules.md section 2): a department or category, a
 * product type, a brand, a brand inside one category, or one product (barcode). CAPABILITY is a seller capability such
 * as FOOD_SERVICE (D11): one approval that several categories refer to.
 */
public enum SellingScopeType {
    CATEGORY("category"),
    PRODUCT_TYPE("product type"),
    BRAND("brand"),
    BRAND_IN_CATEGORY("brand in a category"),
    PRODUCT("product"),
    CAPABILITY("capability");

    private final String noun;

    SellingScopeType(String noun) {
        this.noun = noun;
    }

    /** The word used in sentences: "Approval for this category". */
    public String noun() {
        return noun;
    }
}
