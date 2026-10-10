package com.afrotransact.payments.application;

import java.util.UUID;

import com.afrotransact.platform.security.relationship.FgaObject;

/**
 * OpenFGA relations checked by payments use cases ({@code contracts/openfga/model.fga}, S248). Checked together with
 * the RBAC permission by {@code ResourceAccessChecker}; ignored while {@code at.security.fga.enabled=false}. A payout's
 * organisation comes from payments' database and is passed as a contextual {@code payout#org} tuple.
 */
public final class PaymentsRelations {

    /** Model type of a payout request. */
    public static final String PAYOUT_TYPE = "payout";
    /** On {@code payout}: Finance staff of the organisation's market approve, hold and release it. */
    public static final String PAYOUT_CAN_VIEW = "can_view";
    /**
     * On {@code organization}: balance, statement, label charges, provider fees, payouts (owner, admin, manager and
     * finance members; finance staff of the market; services). Catalog, ads and support members never see payouts.
     */
    public static final String ORG_VIEW_PAYOUTS = "can_view_payouts";
    /** On {@code organization}: request a payout (the organisation's finance role; owners inherit it). */
    public static final String ORG_REQUEST_PAYOUTS = "can_request_payouts";
    /** On {@code platform}: cross-organisation ledger, splits and the payout queue (platform-wide staff, services). */
    public static final String PLATFORM_VIEW_ALL = "can_view_all";

    private PaymentsRelations() {
    }

    static FgaObject payout(UUID id) {
        return FgaObject.of(PAYOUT_TYPE, id);
    }
}
