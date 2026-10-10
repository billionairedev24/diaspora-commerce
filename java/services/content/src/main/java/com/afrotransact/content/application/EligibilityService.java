package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.Eligibility;

/** Public reads of the published markets (checkout, BFF, onboarding country pickers). */
public interface EligibilityService {

    Eligibility check(String country, String region, String postalCode);

    /** Every country of the published version (pickers list all, with their status). */
    List<Country> countries();

    Country country(String code);

    int version();
}
