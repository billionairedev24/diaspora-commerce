package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.Eligibility;

/** Public reads of the published markets (checkout, BFF, onboarding country pickers). */
public interface EligibilityService {

    /**
     * Whether a place is served. {@code city} is optional: given, the state's cities decide too (a city listed
     * PLANNED or OFF is not served; a state that does not cover every city serves only the cities listed LIVE).
     */
    Eligibility check(String country, String region, String postalCode, String city);

    /** Every country of the published version (pickers list all, with their status). */
    List<Country> countries();

    Country country(String code);

    int version();
}
