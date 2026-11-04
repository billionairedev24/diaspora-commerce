package com.afrotransact.fulfilment.domain;

/**
 * The community pickup point of a pickup-point run (delivery-runs' {@code PickupPoint}): the place every drop of the
 * run is made, one handover per order with the customer's code.
 *
 * @param name       place name, e.g. "Bastrop Public Library"
 * @param line1      street address
 * @param city       city
 * @param state      two-letter state
 * @param postalCode ZIP
 * @param hours      hours on the delivery day
 * @param notes      where to park, what to bring
 */
public record RunPickupPoint(String name, String line1, String city, String state, String postalCode, String hours,
                             String notes) {

    /** "Bastrop Public Library, 1100 Church St": one line for a stop. */
    public String display() {
        return line1 == null || line1.isBlank() ? name : name + ", " + line1;
    }
}
