package com.afrotransact.fulfilment.application;

/** Permission keys checked by fulfilment use cases (granted by identity-service roles; see V2__seed_rbac.sql). */
public final class FulfilmentPermissions {

    /** Fulfilment-centre pick, pack and dispatch (platform.fulfilment_ops, operations manager). */
    public static final String OPERATE = "fulfilment:operate";
    /** Driver app (platform.driver, or granted directly). */
    public static final String DELIVERY = "delivery:operate";
    /** Plan routes and assign drivers (platform.dispatcher, fulfilment ops, operations manager). */
    public static final String DISPATCH = "delivery:dispatch";
    /** Schedule and cancel customer return pickups (client scope of tax-returns-service). */
    public static final String RETURNS = "delivery:returns";

    private FulfilmentPermissions() {
    }
}
