package com.afrotransact.offers.application;

import java.util.UUID;

import com.afrotransact.platform.security.relationship.FgaObject;

/**
 * OpenFGA relations checked by offers use cases ({@code contracts/openfga/model.fga}). Checked together with the RBAC
 * permission by {@code ResourceAccessChecker}; ignored while {@code at.security.fga.enabled=false}.
 */
public final class OffersRelations {

    /** Model type of an offer; its owner is passed as a contextual {@code org} tuple from the offers database. */
    public static final String OFFER_TYPE = "offer";
    /** On {@code offer}: see the offer (members of the owning org, staff of its market, platform services). */
    public static final String CAN_VIEW = "can_view";
    /** On {@code offer}: change terms, pause, resume, withdraw (catalog role and above, catalog staff of its market). */
    public static final String CAN_EDIT = "can_edit";
    /** On {@code organization}: create offers and set seller stock for the organisation (it must sell products). */
    public static final String ORG_MANAGE_PRODUCTS = "can_manage_products";
    /** On {@code organization}: list the organisation's offers. */
    public static final String ORG_VIEW_PRODUCTS = "can_view_products";
    /** On {@code platform}: list offers of every organisation (platform-wide staff, first-party services). */
    public static final String PLATFORM_VIEW_ALL = "can_view_all";

    private OffersRelations() {
    }

    static FgaObject offer(UUID id) {
        return FgaObject.of(OFFER_TYPE, id);
    }
}
