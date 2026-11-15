package com.afrotransact.payments.api;

import java.util.List;

/**
 * What the orders module asks payments in-process about a shopper's membership: the early-access hours of pre-order windows
 * ({@code GET /v1/memberships/customers/{id}/entitlements}) and which delivery fees the membership waives
 * ({@code POST /v1/memberships/waivers}); scope {@code payments:service}, entered under the caller's in-process principal.
 */
public interface MembershipBenefitsApi {

    /** @return the customer's early-access hours; 0 for a non-member */
    int earlyAccessHours(String customerId);

    /** Pure: nothing is recorded until the payment is created with the waivers. */
    WaiverQuoteInfo quote(String customerId, long itemsMinor, String currency, List<Fee> fees);

    /** @param kind AFROTRANSACT_DELIVERY, CARRIER, PICKUP or EXPRESS */
    record Fee(String key, String kind, long feeMinor) {
    }

    /** @param benefit FREE_DELIVERY or FREE_SHIPPING */
    record Waiver(String key, String benefit, long waivedMinor) {
    }

    record WaiverQuoteInfo(boolean member, String tierCode, String tierName, List<Waiver> waivers) {

        public WaiverQuoteInfo {
            waivers = waivers == null ? List.of() : List.copyOf(waivers);
        }
    }
}
