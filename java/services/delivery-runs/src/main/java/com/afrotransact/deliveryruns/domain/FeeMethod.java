package com.afrotransact.deliveryruns.domain;

/**
 * How an order is delivered, which is what a delivery fee rule is keyed by (ADR 0005 §3). One rule per method and
 * currency.
 */
public enum FeeMethod {

    /** AfroTransact's own delivery inside a covered location: a flat fee by distance band. */
    AFROTRANSACT_DELIVERY,
    /** A carrier ships the order beyond coverage: the carrier-gateway quote plus handling, or a fallback fee. */
    CARRIER,
    /** The customer collects the order (often free). */
    PICKUP
}
