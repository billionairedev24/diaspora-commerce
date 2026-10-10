package com.afrotransact.offers.domain;

/**
 * Where a seller-fulfilled order ships from ("Ships from Houston, TX"). Country is ISO 3166-1 alpha-2.
 *
 * @param locationId seller-service ship-from location id (S255): required for AFROTRANSACT_DELIVERY offers, where it
 *                   must be one of the seller's locations with AfroTransact pickup enabled; optional otherwise
 */
public record ShipFrom(String city, String region, String country, String postalCode, String locationId) {

    /** A ship-from without a seller location reference. */
    public ShipFrom(String city, String region, String country, String postalCode) {
        this(city, region, country, postalCode, null);
    }

    public String label() {
        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isBlank()) {
            sb.append(city.trim());
        }
        if (region != null && !region.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(region.trim());
        }
        if (sb.isEmpty() && country != null) {
            sb.append(country);
        }
        return sb.toString();
    }
}
