package com.afrotransact.trustops.domain;

/** What the customer claims (design: Disputes & returns case types). */
public enum ClaimType {
    ITEM_NOT_RECEIVED,
    DAMAGED_ITEM,
    WRONG_ITEM,
    NOT_AS_DESCRIBED,
    BOOKING_NO_SHOW,
    OTHER
}
