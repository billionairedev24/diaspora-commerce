package com.afrotransact.deliveryruns.domain;

/** What shoppers see where pre-order is off. */
public enum Fallback {
    /** Shoppers can still buy; it ships by UPS Ground at the rate quoted at checkout (3–5 days). */
    SHIP,
    /** No checkout; shoppers join the waitlist and are told when the next pre-order opens. */
    WAITLIST
}
