package com.afrotransact.bookings.domain;

/** The customer's deliver-to place: ZIP, city, state and the ZIP centroid. */
public record CustomerPlace(String zip, String city, String state, double lat, double lon) {
}
