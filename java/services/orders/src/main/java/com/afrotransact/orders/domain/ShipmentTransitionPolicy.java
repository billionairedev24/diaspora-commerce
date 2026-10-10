package com.afrotransact.orders.domain;

import java.util.Set;

/** The shipment state machine with its actor rules per fulfilment type (architecture §17c). */
public interface ShipmentTransitionPolicy {

    /**
     * Decides whether {@code actor} may move {@code shipment} to {@code to}.
     *
     * @throws TransitionRefused when the actor never owns that step for the shipment's fulfilment type (forbidden) or
     *                           the step is not reachable from the current status
     */
    TransitionDecision decide(Shipment shipment, ShipmentStatus to, ActorKind actor);

    /** Statuses {@code actor} may move the shipment to right now (for "next action" buttons). */
    Set<ShipmentStatus> nextFor(Shipment shipment, ActorKind actor);
}
