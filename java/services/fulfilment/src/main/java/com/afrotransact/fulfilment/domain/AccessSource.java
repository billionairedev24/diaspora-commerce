package com.afrotransact.fulfilment.domain;

/** Who changed a driver's access. */
public enum AccessSource {
    /** identity-service event (role / permission / account change). */
    IDENTITY,
    /** Inferred from the caller's token (permission present or missing). */
    TOKEN,
    /** Suspended or reinstated by a dispatcher; a fresh token does not lift a suspension. */
    DISPATCH
}
