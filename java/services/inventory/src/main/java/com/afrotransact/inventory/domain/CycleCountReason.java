package com.afrotransact.inventory.domain;

/** Why a counted quantity differs from the system: required on every variance line before it is posted. */
public enum CycleCountReason {
    /** Units found that were not on the system (e.g. put away in the wrong bin). */
    FOUND,
    /** Units missing with no other explanation. */
    LOST,
    /** Units damaged in storage. */
    DAMAGED,
    /** Earlier pick of the wrong lot or quantity. */
    MISPICK,
    /** Received more or fewer units than were booked in. */
    RECEIVING_ERROR,
    /** Suspected theft (raises a loss-prevention review). */
    THEFT,
    /** A wrong quantity was keyed in earlier. */
    DATA_ENTRY,
    OTHER
}
