package com.afrotransact.offers.domain;

/** Where a seller-fulfilled order ships from ("Ships from Houston, TX"). Country is ISO 3166-1 alpha-2. */
public record ShipFrom(String city, String region, String country, String postalCode) {

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
