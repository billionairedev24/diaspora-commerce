package com.afrotransact.orders.application;

/** Permission keys checked by the orders use cases (granted by identity-service roles and client scopes). */
public final class OrdersPermissions {

    /** View orders and shipments (sellers: only their organisation's shipments). */
    public static final String READ = "orders:read";
    /** Seller-fulfilled status steps (sellers) and admin oversight steps (support). */
    public static final String UPDATE_STATUS = "orders:update-status";
    /** AfroTransact-fulfilled shipments, cancellations and reroutes (operations, dispatch). */
    public static final String MANAGE = "orders:manage";
    /** Fulfilment-centre pick, pack and dispatch. */
    public static final String FULFILMENT = "fulfilment:operate";
    /** Driver app (AfroTransact Delivery couriers). */
    public static final String DELIVERY = "delivery:operate";

    private OrdersPermissions() {
    }
}
