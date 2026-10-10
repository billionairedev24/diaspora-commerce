package com.afrotransact.catalog.application;

import java.util.UUID;

import com.afrotransact.platform.security.relationship.FgaObject;

/**
 * OpenFGA relations checked by catalog use cases ({@code contracts/openfga/model.fga}, S248). Checked together with the
 * RBAC permission by {@code ResourceAccessChecker}; ignored while {@code at.security.fga.enabled=false}. The owner of a
 * product always comes from catalog's database and is passed as a contextual {@code product#org} tuple.
 */
public final class CatalogRelations {

    /** Model type of a product page; one object per catalog product id. */
    public static final String PRODUCT_TYPE = "product";
    /** On {@code product}: see the product in any status (members of the owning org, staff of its market, services). */
    public static final String CAN_VIEW = "can_view";
    /** On {@code product}: pause / resume (catalog role and above, catalog staff of its market, services). */
    public static final String CAN_EDIT = "can_edit";
    /** On {@code organization}: create products for the organisation (it must sell products). */
    public static final String ORG_MANAGE_PRODUCTS = "can_manage_products";
    /** On {@code organization}: list the organisation's products. */
    public static final String ORG_VIEW_PRODUCTS = "can_view_products";
    /** On {@code platform}: list products of every organisation (platform-wide staff, first-party services). */
    public static final String PLATFORM_VIEW_ALL = "can_view_all";

    private CatalogRelations() {
    }

    static FgaObject product(UUID id) {
        return FgaObject.of(PRODUCT_TYPE, id);
    }
}
