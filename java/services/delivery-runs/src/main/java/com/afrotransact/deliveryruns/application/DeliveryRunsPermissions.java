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
    /** Staff: open, edit, publish, pause pre-orders; creatives; settings, payment setup and coverage areas. */
    public static final String PREORDERS_MANAGE = "preorders:manage";
    /** Staff: CSV exports of pre-order orders, picking lists and advertorial reports (audit-logged). */
    public static final String PREORDERS_EXPORT = "preorders:export";
    /** Customer service: add a late pre-order on behalf of a customer. */
    public static final String PREORDERS_LATE_ADD = "preorders:late-add";

    private DeliveryRunsPermissions() {
    }
}
