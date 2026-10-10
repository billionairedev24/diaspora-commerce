package com.afrotransact.orders.domain;

/**
 * Outcome of {@link Shipment#transition}.
 *
 * @param shipment the shipment after the call (unchanged unless {@code applied()})
 * @param change   the recorded change when applied, else null
 * @param kind     what happened
 * @param note     why an event was ignored
 */
public record TransitionResult(Shipment shipment, StatusChange change, TransitionDecision.Kind kind, String note) {

    public boolean applied() {
        return kind == TransitionDecision.Kind.ALLOW;
    }
}
