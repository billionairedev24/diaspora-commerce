package com.afrotransact.seller.domain;

/** Postal address (US: state is the two-letter code, postal code a ZIP or ZIP+4). */
public record Address(String line1, String line2, String city, String state, String postalCode, String country) {
}
