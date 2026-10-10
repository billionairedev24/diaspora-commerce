package com.afrotransact.inventory.domain;

/**
 * A difference between the inbound plan and what the FC received.
 *
 * @param qty units short, over, damaged or unexpected
 */
public record InboundDiscrepancy(String sku, String atin, Type type, int expectedQty, int receivedQty, int qty) {

    /** Kinds of discrepancy. */
    public enum Type {
        /** Fewer units received than announced (reported at close). */
        SHORT,
        /** More units received than announced. */
        OVER,
        /** Units received damaged (quarantined). */
        DAMAGED,
        /** A SKU that was not on the plan. */
        UNEXPECTED
    }
}
