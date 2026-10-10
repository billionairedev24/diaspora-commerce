package com.afrotransact.payments.domain;

import java.util.List;

/** Payout-on-request rules (seller & provider portals, "Request payout"). */
public interface PayoutPolicy {

    /** Minimum amount of one payout (USD 25.00 by default). */
    Money minimum(String currency);

    /**
     * Validates a request and returns the amount to pay out.
     *
     * @param available     balance available to request (transferred after the settlement hold, minus requests)
     * @param requestsToday the organisation's requests on the same (policy-zone) day
     */
    Money validate(Requester requester, PayoutAsk ask, Money available, List<PayoutRequest> requestsToday);

    /** Whether the organisation may still request today. */
    boolean canRequestToday(List<PayoutRequest> requestsToday);
}
