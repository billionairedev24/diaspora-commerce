package com.afrotransact.offers.adapter.out.module;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.offers.port.ListingEntitlementsClient;
import com.afrotransact.seller.api.SellerLookupApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link ListingEntitlementsClient} through seller's published API inside this JVM (mode {@code in-process}): the same answer as
 * {@code GET /v1/orgs/{orgId}/entitlements}, entered as offers' caller principal (scope {@code sellers:entitlements.read}).
 * Not cached: there is no round trip to save. As over HTTP, a failure never blocks the seller: the listing is not limited.
 */
@Component
@ConditionalOnExpression("'${at.offers.seller.mode:${at.offers.mode:http}}' == 'in-process'")
class InProcessListingEntitlementsClient implements ListingEntitlementsClient {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessListingEntitlementsClient.class);

    private final InProcessLink<SellerLookupApi> seller;

    InProcessListingEntitlementsClient(ObjectProvider<SellerLookupApi> api, ObjectProvider<CallerPrincipals> callers,
                                       ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(SellerLookupApi.class, true, false).length > 0, "offers", "seller",
                "at.offers.seller.mode");
        this.seller = new InProcessLink<>("offers", api::getObject, callers::getObject);
    }

    @Override
    public ListingAllowance allowance(String sellerOrgId) {
        try {
            SellerLookupApi.ListingEntitlements e = seller.call(api -> api.listingEntitlements(sellerOrgId));
            return e == null || e.listingsUnlimited() ? ListingAllowance.UNLIMITED
                    : new ListingAllowance(e.planName(), e.listings());
        } catch (RuntimeException ex) {
            LOG.warn("seller entitlements of {} unavailable in-process ({}); not limiting this listing", sellerOrgId,
                    ex.getMessage());
            return ListingAllowance.UNLIMITED;
        }
    }
}
