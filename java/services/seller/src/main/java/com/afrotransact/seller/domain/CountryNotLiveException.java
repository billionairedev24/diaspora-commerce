package com.afrotransact.seller.domain;

import java.util.Map;

/** The applicant picked a country where AfroTransact is not open for sellers yet ({@code seller.country-not-live}). */
public class CountryNotLiveException extends SellerRuleViolation {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "seller.country-not-live";

    private final String country;

    public CountryNotLiveException(String country) {
        super(CODE, "We're not open for sellers in " + country + " yet. Join the launch list to hear when we are.",
                Map.of("business.country", "Not live yet"));
        this.country = country;
    }

    public String country() {
        return country;
    }
}
