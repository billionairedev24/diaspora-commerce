package com.afrotransact.bookings.domain;

/**
 * Whether a provider serves a customer's place.
 *
 * @param coverage LOCAL (serves the place) or NONE
 * @param reason   why a LOCAL match happened
 * @param miles    straight-line miles from the provider's base to the customer's ZIP centroid
 */
public record AreaMatch(Coverage coverage, Reason reason, double miles) {

    /** How a provider relates to the customer's place. */
    public enum Coverage { LOCAL, NONE }

    /**
     * Why a provider serves the place: RADIUS (comes to the customer, inside the provider's own radius),
     * SHOPPER_RADIUS (the customer goes to the provider, inside the distance the shopper chose or any distance),
     * NAMED_AREA (a named extra area).
     */
    public enum Reason { RADIUS, SHOPPER_RADIUS, NAMED_AREA, NONE }

    public boolean local() {
        return coverage == Coverage.LOCAL;
    }
}
