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
    public static final String RUN_CANCELLED = "delivery-runs.run.cancelled";
    public static final String RUN_OUT_FOR_DELIVERY = "delivery-runs.run.out_for_delivery";
    public static final String RUN_COMPLETED = "delivery-runs.run.completed";

    public static final String SUBSCRIPTION_CREATED = "delivery-runs.subscription.created";
    public static final String SUBSCRIPTION_CONFIRMED = "delivery-runs.subscription.confirmed";
    public static final String SUBSCRIPTION_CANCELLED = "delivery-runs.subscription.cancelled";

    private DeliveryRunsEvents() {
    }
}
