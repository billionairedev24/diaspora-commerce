package com.afrotransact.offers.port;

/**
 * seller-service {@code GET /v1/orgs/{orgId}/entitlements} (offers' client-credentials token, scope
 * {@code sellers:entitlements.read}): how many live listings the organisation's plan allows. There is no limit on shops
 * or users, only on listings.
 */
public interface ListingEntitlementsClient {

    /**
     * What the plan allows. When seller-service is unavailable the answer is {@link ListingAllowance#UNLIMITED}: a
     * seller is never blocked from listing because billing is down (the next listing is checked again).
     */
    ListingAllowance allowance(String sellerOrgId);

    /**
     * @param planName the plan's name, for the message the seller reads
     * @param limit    the most live listings, null when unlimited
     */
    record ListingAllowance(String planName, Long limit) {

        public static final ListingAllowance UNLIMITED = new ListingAllowance(null, null);

        public boolean allows(long live) {
            return limit == null || live < limit;
        }
    }
}
