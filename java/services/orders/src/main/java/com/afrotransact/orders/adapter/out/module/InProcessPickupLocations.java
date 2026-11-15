package com.afrotransact.orders.adapter.out.module;

import java.util.Optional;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.orders.port.PickupLocations;
import com.afrotransact.seller.api.ShipFromAddressApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link PickupLocations} through offers' {@link CheckoutOffersApi} (the offer's pickup location id) and seller's
 * {@link ShipFromAddressApi} (the location itself) inside this JVM. Used when both edges are in-process.
 */
@Component
@ConditionalOnExpression("'${at.orders.offers.mode:${at.orders.mode:http}}' == 'in-process' "
        + "and '${at.orders.seller.mode:${at.orders.mode:http}}' == 'in-process'")
class InProcessPickupLocations implements PickupLocations {

    private final ObjectProvider<CheckoutOffersApi> offers;
    private final ObjectProvider<ShipFromAddressApi> sellers;
    private final ModuleUpstream upstream;

    InProcessPickupLocations(ObjectProvider<CheckoutOffersApi> offers, ObjectProvider<ShipFromAddressApi> sellers,
                             ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(CheckoutOffersApi.class, true, false).length > 0,
                "orders", "offers", "at.orders.offers.mode");
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ShipFromAddressApi.class, true, false).length > 0,
                "orders", "seller", "at.orders.seller.mode");
        this.offers = offers;
        this.sellers = sellers;
        this.upstream = upstream;
    }

    @Override
    public Optional<PickupLocation> forOffer(String offerId) {
        if (offerId == null || offerId.isBlank()) {
            return Optional.empty();
        }
        Optional<String> locationId = upstream.call("offers", () -> offers.getObject().shipFromLocationId(offerId))
                .filter(id -> !id.isBlank());
        if (locationId.isEmpty()) {
            return Optional.empty();
        }
        return upstream.call("seller", () -> sellers.getObject().location(locationId.get()))
                .map(l -> new PickupLocation(locationId.get(), l.orgId(), l.pickupEnabled(), l.lat(), l.lng()));
    }
}
