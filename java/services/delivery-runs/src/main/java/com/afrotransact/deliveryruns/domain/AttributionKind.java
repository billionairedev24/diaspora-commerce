package com.afrotransact.deliveryruns.domain;

/** What an attributed counter counts (S262). */
public enum AttributionKind {
    /** A visit to a location page or run link that carried UTM tags. */
    VISIT,
    /** A subscription that became active (a confirmed email, or a signed-in shopper's) after such a visit. */
    SIGNUP
}
