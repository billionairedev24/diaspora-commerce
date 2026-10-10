package com.afrotransact.content.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.Eligibility;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class EligibilityServiceImpl implements EligibilityService {

    private final MarketSnapshotCache published;

    EligibilityServiceImpl(MarketSnapshotCache published) {
        this.published = published;
    }

    @Override
    public Eligibility check(String country, String region, String postalCode) {
        if (country == null || country.isBlank()) {
            throw new ContentRuleViolation("content.country-required", "country is required",
                    Map.of("country", "Required"));
        }
        return published.get().eligibility(country, region, postalCode);
    }

    @Override
    public List<Country> countries() {
        return published.get().countries();
    }

    @Override
    public Country country(String code) {
        return published.get().country(code)
                .orElseThrow(() -> new NotFoundException("content.country-not-found", "No country " + code));
    }

    @Override
    public int version() {
        return published.get().version();
    }
}
