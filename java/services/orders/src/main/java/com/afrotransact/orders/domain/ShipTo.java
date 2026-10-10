package com.afrotransact.orders.domain;

/** Delivery address snapshot taken at checkout (the address book may change later). */
public record ShipTo(String addressId, String fullName, String phone, String line1, String line2, String city,
                     String state, String postalCode, String country, String instructions) {

    /** "City, ST" for order lists. */
    public String summary() {
        String c = city == null ? "" : city;
        return state == null || state.isBlank() ? c : c + ", " + state;
    }
}
