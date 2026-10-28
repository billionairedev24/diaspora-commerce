package com.afrotransact.orders.application;

/** Permission keys checked by the orders use cases (granted by identity-service roles and client scopes). */
public final class OrdersPermissions {

    /** View orders and shipments (sellers: only their organisation's shipments). */
    public static final String READ = "orders:read";
    /** Service-to-service order lookup (tax-returns-service, trust-ops-service client scope). */
    public static final String LOOKUP = "orders:lookup";
    /**
     * Verified-purchase check (S324): catalog-service asks whether a customer received a product. A narrow scope: it
     * answers yes or no with the delivering order, never the order's contents or address.
     */
    public static final String PURCHASE_CHECK = "orders:purchase-check";
    /** Seller-fulfilled status steps (sellers) and admin oversight steps (support). */
    public static final String UPDATE_STATUS = "orders:update-status";
    /** AfroTransact-fulfilled shipments, cancellations and reroutes (operations, dispatch). */
    public static final String MANAGE = "orders:manage";
    /** Fulfilment-centre pick, pack and dispatch. */
    public static final String FULFILMENT = "fulfilment:operate";
    /** Driver app (AfroTransact Delivery couriers). */
    public static final String DELIVERY = "delivery:operate";
    /** fulfilment-service relaying driver events on behalf of a driver (queued courier events). */
    public static final String RELAY = "delivery:relay";
    /**
     * S272: create orders on a customer's behalf (delivery-runs-service late pre-order adds; service clients only).
     */
    public static final String ON_BEHALF = "orders:on-behalf";
    /** gateway-bff resolving a late pre-order add's pay-now link for the storefront pay page (token checked). */
    public static final String PAY_LINK = "orders:pay-link";
    /** Dispatch (staff or fulfilment-service): read delivery briefs to plan routes. */
    public static final String DISPATCH = "delivery:dispatch";

    private OrdersPermissions() {
    }
}
