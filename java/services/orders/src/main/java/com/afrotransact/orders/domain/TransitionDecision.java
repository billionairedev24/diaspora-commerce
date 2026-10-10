package com.afrotransact.orders.domain;

/** Result of asking the state machine whether a transition may happen. Refusals are thrown instead. */
public record TransitionDecision(Kind kind, String note) {

    /** What to do with the request. */
    public enum Kind {
        /** Apply the transition. */
        ALLOW,
        /** Already in the requested status: nothing to do (idempotent). */
        UNCHANGED,
        /** Out-of-order / stale external event: drop it without moving the shipment backwards. */
        IGNORE
    }

    public static TransitionDecision allow() {
        return new TransitionDecision(Kind.ALLOW, null);
    }

    public static TransitionDecision unchanged() {
        return new TransitionDecision(Kind.UNCHANGED, "already in that status");
    }

    public static TransitionDecision ignore(String note) {
        return new TransitionDecision(Kind.IGNORE, note);
    }
}
