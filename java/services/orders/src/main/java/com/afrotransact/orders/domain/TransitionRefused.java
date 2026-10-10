package com.afrotransact.orders.domain;

/**
 * A shipment transition was refused by the state machine: either the actor never owns that step for this
 * fulfilment type ({@link #forbidden()}, HTTP 403) or the step is not reachable from the current status (HTTP 409).
 */
public class TransitionRefused extends RuntimeException {

    public static final String FORBIDDEN = "orders.transition-forbidden";
    public static final String INVALID = "orders.invalid-transition";
    public static final String CANCEL_WINDOW_CLOSED = "orders.cancel-window-closed";

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean forbidden;

    private TransitionRefused(String code, String message, boolean forbidden) {
        super(message);
        this.code = code;
        this.forbidden = forbidden;
    }

    public static TransitionRefused forbidden(ActorKind actor, ShipmentStatus to, FulfilmentType type) {
        return new TransitionRefused(FORBIDDEN, actorLabel(actor) + " may not set a " + label(type)
                + " shipment to " + to.wire(), true);
    }

    public static TransitionRefused forbidden(String message) {
        return new TransitionRefused(FORBIDDEN, message, true);
    }

    public static TransitionRefused invalid(ShipmentStatus from, ShipmentStatus to) {
        return new TransitionRefused(INVALID, "A shipment cannot move from " + from.wire() + " to " + to.wire(),
                false);
    }

    public static TransitionRefused cancelWindowClosed(ShipmentStatus from) {
        return new TransitionRefused(CANCEL_WINDOW_CLOSED, "This shipment is already " + from.wire()
                + "; it can only be cancelled before it is packed", false);
    }

    public String code() {
        return code;
    }

    public boolean forbidden() {
        return forbidden;
    }

    private static String actorLabel(ActorKind actor) {
        return switch (actor) {
            case CUSTOMER -> "The customer";
            case SELLER -> "The seller";
            case PLATFORM_ADMIN -> "Platform admins";
            case FULFILMENT_OPS -> "Fulfilment ops";
            case CARRIER -> "Carriers";
            case COURIER -> "AfroTransact Delivery couriers";
            case SYSTEM -> "The checkout saga";
        };
    }

    private static String label(FulfilmentType type) {
        return switch (type) {
            case SELLER_FULFILLED -> "seller-fulfilled";
            case FBA_AFROTRANSACT -> "Fulfilled-by-AfroTransact";
            case RETAIL_1P -> "AfroTransact Retail";
        };
    }
}
