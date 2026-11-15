package com.afrotransact.content.adapter.in.module;

import com.afrotransact.content.api.MarketEligibilityApi;
import com.afrotransact.content.application.EligibilityService;
import com.afrotransact.module.InProcessCalls;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link MarketEligibilityApi}: the anonymous eligibility check behind
 * {@code GET /v1/markets/eligibility} (no city, as orders asks). No scope over HTTP, none here; a caller principal is still
 * required so that no code path reaches it unnamed.
 */
@Component
class MarketEligibilityApiAdapter implements MarketEligibilityApi {

    private final EligibilityService eligibility;

    MarketEligibilityApiAdapter(EligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    @Override
    public Eligibility check(String country, String state, String zip) {
        InProcessCalls.require();
        var e = eligibility.check(country, state, zip, null);
        return new Eligibility(e.deliveryAllowed(), e.reason(), e.message());
    }
}
