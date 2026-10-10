package com.afrotransact.orders.domain;

/**
 * Delivery address snapshot taken at checkout (the address book may change later).
 *
 * @param lat latitude when the address book knows it (geo-validated addresses), else null
 * @param lng longitude when known, else null
 */
public record ShipTo(String addressId, String fullName, String phone, String line1, String line2, String city,
                     String state, String postalCode, String country, String instructions, Double lat, Double lng) {

    /** An address without a known position. */
    public ShipTo(String addressId, String fullName, String phone, String line1, String line2, String city,
                  String state, String postalCode, String country, String instructions) {
        this(addressId, fullName, phone, line1, line2, city, state, postalCode, country, instructions, null, null);
    }

    /** "City, ST" for order lists. */
    public String summary() {
        String c = city == null ? "" : city;
        return state == null || state.isBlank() ? c : c + ", " + state;
    }
}
