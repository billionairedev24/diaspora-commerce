package com.afrotransact.orders.port;

import java.util.Optional;

/** content-service {@code GET /v1/markets/eligibility}. May be unavailable: callers then allow checkout. */
public interface MarketEligibility {

    /** Empty when content-service does not answer. */
    Optional<Result> check(String country, String state, String zip);

    /** Whether delivery (and so checkout) is allowed at the address. */
    record Result(boolean deliveryAllowed, String reason, String message) {
    }
}
