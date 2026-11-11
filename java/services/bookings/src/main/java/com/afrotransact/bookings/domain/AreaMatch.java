package com.afrotransact.bookings.domain;

/**
 * Whether a provider serves a customer's place.
 *
 * @param coverage LOCAL (serves the place), NEAREST (outside its usual coverage but close enough to suggest) or NONE
 * @param reason   why a LOCAL match happened
 * @param miles    straight-line miles from the provider's base to the customer's ZIP centroid
 */
public record AreaMatch(Coverage coverage, Reason reason, double miles) {

    /** How a provider relates to the customer's place. */
    public enum Coverage { LOCAL, NEAREST, NONE }

    /** Why a provider serves the place. */
    public enum Reason { RADIUS, TRAVEL_LIMIT, NAMED_AREA, NONE }

    public boolean local() {
        return coverage == Coverage.LOCAL;
    }
}
