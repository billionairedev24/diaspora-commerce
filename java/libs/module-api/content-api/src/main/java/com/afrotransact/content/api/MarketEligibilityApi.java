package com.afrotransact.content.api;

/**
 * Whether AfroTransact delivers to a place ({@code GET /v1/markets/eligibility}): the public read orders makes at checkout.
 * No scope is needed over HTTP, so none is needed here; the call still runs under an in-process caller principal.
 */
public interface MarketEligibilityApi {

    /**
     * @param country ISO 3166-1 alpha-2 code
     * @param state   the region, null when unknown
     * @param zip     the postal code, null when unknown
     */
    Eligibility check(String country, String state, String zip);

    /** @param reason a stable code such as {@code region-disabled}, null when delivery is allowed */
    record Eligibility(boolean deliveryAllowed, String reason, String message) {
    }
}
