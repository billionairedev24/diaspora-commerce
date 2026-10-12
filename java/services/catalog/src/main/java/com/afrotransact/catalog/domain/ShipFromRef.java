package com.afrotransact.catalog.domain;

/** The seller's ship-from location a listing ships from (snapshot of the seller-service address). */
public record ShipFromRef(String locationId, String label, String city, String region, String country,
                          String postalCode) {
}
