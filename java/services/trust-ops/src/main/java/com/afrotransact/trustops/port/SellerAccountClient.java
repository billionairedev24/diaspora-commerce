package com.afrotransact.trustops.port;

/**
 * seller-service account enforcement. seller.yaml has no suspend endpoint, so payouts are held and released through
 * the Stripe-compliance hold: {@code GET /v1/compliance/accounts} (org → account id) then
 * {@code POST /v1/compliance/accounts/{accountId}/payouts/pause|resume} (compliance:hold).
 */
public interface SellerAccountClient {

    /** @return false when the organisation has no connected account (nothing to hold) */
    boolean holdPayouts(String orgId, String note);

    /** @return false when the organisation has no connected account */
    boolean releasePayouts(String orgId, String note);
}
