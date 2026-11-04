package com.afrotransact.deliveryruns.application;

/**
 * Event types published by delivery-runs (documented in contracts/asyncapi/fragments/delivery-runs.yaml). Every event
 * uses aggregate type {@code delivery-runs}, so all go to topic {@code at.delivery-runs.events}; the key is the run
 * id or the subscription id.
 */
public final class DeliveryRunsEvents {

    public static final String AGGREGATE = "delivery-runs";

    public static final String RUN_ANNOUNCED = "delivery-runs.run.announced";
    public static final String RUN_OPENED = "delivery-runs.run.opened";
    public static final String RUN_CUTOFF_REMINDER = "delivery-runs.run.cutoff_reminder";
    public static final String RUN_CONFIRMED = "delivery-runs.run.confirmed";
    public static final String RUN_EXTENDED = "delivery-runs.run.extended";
    /** The first order was collected from its seller (S264): the sweep has started. */
    public static final String RUN_COLLECTING = "delivery-runs.run.collecting";
    public static final String RUN_CANCELLED = "delivery-runs.run.cancelled";
    public static final String RUN_OUT_FOR_DELIVERY = "delivery-runs.run.out_for_delivery";
    public static final String RUN_COMPLETED = "delivery-runs.run.completed";

    /**
     * A location was created, edited, switched on or switched off by staff (audit: who, when and, for a switch, why).
     * Keyed by location id.
     */
    public static final String LOCATION_CHANGED = "delivery-runs.location.changed";

    public static final String SUBSCRIPTION_CREATED = "delivery-runs.subscription.created";
    public static final String SUBSCRIPTION_CONFIRMED = "delivery-runs.subscription.confirmed";
    public static final String SUBSCRIPTION_CANCELLED = "delivery-runs.subscription.cancelled";

    // Pre-orders (S270, S271): keyed by window id (settings by "preorder-settings").
    public static final String PREORDER_WINDOW_PUBLISHED = "preorder.window.published";
    public static final String PREORDER_WINDOW_UPDATED = "preorder.window.updated";
    public static final String PREORDER_WINDOW_PAUSED = "preorder.window.paused";
    public static final String PREORDER_WINDOW_RESUMED = "preorder.window.resumed";
    public static final String PREORDER_WINDOW_OPENED = "preorder.window.opened";
    public static final String PREORDER_WINDOW_CLOSED = "preorder.window.closed";
    public static final String PREORDER_WINDOW_DELIVERED = "preorder.window.delivered";
    /**
     * The window closed below its minimum and waits for staff (cancel or proceed): the notification for staff.
     */
    public static final String PREORDER_WINDOW_DECISION_NEEDED = "preorder.window.decision_needed";
    /** Staff proceeded below the minimum, cancelled the window, or closed it early: who, when and why. */
    public static final String PREORDER_WINDOW_DECIDED = "preorder.window.decided";
    /**
     * The window was cancelled: orders-service refunds every order in {@code orders[]} in full and releases the
     * stock; notifications tells the customers.
     */
    public static final String PREORDER_WINDOW_CANCELLED = "preorder.window.cancelled";
    public static final String PREORDER_SETTINGS_CHANGED = "preorder.settings.changed";
    /** Coverage of one region edited in place (rows added, removed or switched), with the actor. */
    public static final String PREORDER_AREAS_CHANGED = "preorder.areas.changed";
    public static final String PREORDER_COMMS_DUE = "preorder.comms.due";
    public static final String PREORDER_LATE_ORDER_ADDED = "preorder.late_order.added";
    public static final String PREORDER_EXPORT_CREATED = "preorder.export.created";
    public static final String PREORDER_WAITLIST_JOINED = "preorder.waitlist.joined";

    private DeliveryRunsEvents() {
    }
}
