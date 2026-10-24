package com.afrotransact.deliveryruns.domain;

/** Where the amount of a resolved fee came from. */
public enum FeeSource {

    /** The method's rule (a distance band, the pickup fee, or the free-over threshold). */
    RULE,
    /** Staff set a fee on the window itself. */
    WINDOW_OVERRIDE,
    /** The carrier-gateway quote plus the rule's handling fee. */
    CARRIER_QUOTE,
    /** No carrier quote was available: the rule's fallback fee. */
    CARRIER_FALLBACK
}
