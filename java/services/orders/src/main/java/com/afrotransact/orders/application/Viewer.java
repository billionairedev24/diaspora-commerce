package com.afrotransact.orders.application;

/**
 * Who is looking at an order, which decides what the view contains.
 *
 * @param kind  customer (own order, handover codes revealed), seller (own shipments only) or staff (everything,
 *              never the codes)
 * @param orgId the seller organisation for seller viewers
 */
public record Viewer(Kind kind, String orgId) {

    /** Viewer kinds. */
    public enum Kind {
        CUSTOMER,
        SELLER,
        STAFF
    }
}
