package com.afrotransact.taxreturns.domain.returns;

/** A postal address used on return labels (customer origin, seller or FC destination). */
public record ReturnAddress(String name, String line1, String line2, String city, String state, String postalCode,
                            String country, String phone) {

    public ReturnAddress {
        country = country == null || country.isBlank() ? "US" : country;
    }

    /** Line 1, city and postal code are present. */
    public boolean complete() {
        return notBlank(line1) && notBlank(city) && notBlank(postalCode);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
