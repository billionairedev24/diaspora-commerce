package com.afrotransact.offers.adapter.out.module;

import java.util.Optional;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.offers.port.PickupLocationClient;
import com.afrotransact.seller.api.SellerLookupApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link PickupLocationClient} through seller's published API inside this JVM (mode {@code in-process}): the same answer as
 * {@code GET /v1/ship-from-locations/{id}}, entered as offers' caller principal (scope {@code sellers:ship-from.read}). An
 * unknown location is an empty answer, as a 404 is over HTTP; anything else, including a refusal of the principal, is
 * {@link SellerUnavailableException}.
 */
@Component
@ConditionalOnExpression("'${at.offers.mode:http}' == 'in-process'")
class InProcessPickupLocationClient implements PickupLocationClient {

    private final InProcessLink<SellerLookupApi> seller;

    InProcessPickupLocationClient(ObjectProvider<SellerLookupApi> api, ObjectProvider<CallerPrincipals> callers) {
        this.seller = new InProcessLink<>("offers", api::getObject, callers::getObject);
    }

    @Override
    public Optional<PickupLocation> find(String locationId) {
        try {
            return seller.call(api -> api.shipFromLocation(locationId)).map(l -> new PickupLocation(l.id(), l.orgId(),
                    l.pickupEnabled(), l.city(), l.state(), l.country(), l.postalCode()));
        } catch (RuntimeException ex) {
            throw new SellerUnavailableException("seller refused or failed the in-process call: " + ex.getMessage(), ex);
        }
    }
}
