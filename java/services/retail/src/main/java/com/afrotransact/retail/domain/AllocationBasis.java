package com.afrotransact.retail.domain;

/** How a charge is spread across receipt lines. */
public enum AllocationBasis {
    /** Proportional to the line's FOB value in base currency. */
    VALUE,
    /** Proportional to the line's gross weight (quantity × unit weight). */
    WEIGHT,
    /** Proportional to the line's unit count. */
    QUANTITY
}
