package com.afrotransact.fulfilment.application;

/** Event types published on {@code at.fulfilment.events} (contracts/asyncapi/fragments/fulfilment.yaml). */
public final class FulfilmentEvents {

    public static final String AGGREGATE = "fulfilment";

    public static final String TASK_CREATED = "fulfilment.task.created";
    public static final String TASK_PICKED = "fulfilment.task.picked";
    public static final String TASK_PICK_EXCEPTION = "fulfilment.task.pick_exception";
    public static final String TASK_PACKED = "fulfilment.task.packed";
    public static final String TASK_READY = "fulfilment.task.ready";
    public static final String TASK_CANCELLED = "fulfilment.task.cancelled";

    public static final String ROUTE_PLANNED = "fulfilment.route.planned";
    public static final String ROUTE_ASSIGNED = "fulfilment.route.assigned";
    public static final String ROUTE_STARTED = "fulfilment.route.started";
    public static final String ROUTE_COMPLETED = "fulfilment.route.completed";
    public static final String ROUTE_CANCELLED = "fulfilment.route.cancelled";

    public static final String STOP_DELIVERED = "fulfilment.stop.delivered";
    public static final String STOP_FAILED = "fulfilment.stop.failed";
    public static final String STOP_SKIPPED = "fulfilment.stop.skipped";
    public static final String STOP_REASSIGNED = "fulfilment.stop.reassigned";

    public static final String VEHICLE_DEFECT = "fulfilment.vehicle.defect_reported";
    /** High priority: dispatch must react now. */
    public static final String SOS_RAISED = "fulfilment.driver.sos_raised";
    public static final String ACCESS_REVOKED = "fulfilment.driver.access_revoked";
    public static final String FORWARD_FAILED = "fulfilment.orders.forward_failed";

    private FulfilmentEvents() {
    }
}
