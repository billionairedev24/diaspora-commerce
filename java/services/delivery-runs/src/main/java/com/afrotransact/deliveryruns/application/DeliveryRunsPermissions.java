package com.afrotransact.deliveryruns.application;

/** Permission keys checked by delivery-runs use cases (granted by identity-service roles and client scopes). */
public final class DeliveryRunsPermissions {

    /** Staff and service reads: locations, runs, run orders, economics, subscriber counts, dispatch lists. */
    public static final String READ = "delivery-runs:read";
    /** Staff: create and edit locations and runs, run transitions. */
    public static final String MANAGE = "delivery-runs:manage";
    /** orders-service client: reserve and release run slots for orders. */
    public static final String RESERVE = "delivery-runs:reserve";
    /** notifications-service client: page through a location's opted-in subscribers per channel. */
    public static final String AUDIENCE = "delivery-runs:audience.read";

    private DeliveryRunsPermissions() {
    }
}
