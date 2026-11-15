package com.afrotransact.orders.adapter.out.module;

import java.util.Optional;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.orders.port.OfferInventory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** {@link OfferInventory} through offers' {@link CheckoutOffersApi} inside this JVM (mode {@code in-process}). */
@Component
@ConditionalOnExpression("'${at.orders.offers.mode:${at.orders.mode:http}}' == 'in-process'")
class InProcessOfferInventory implements OfferInventory {

    private static final String SERVICE = "offers";

    private final ObjectProvider<CheckoutOffersApi> api;
    private final ModuleUpstream upstream;

    InProcessOfferInventory(ObjectProvider<CheckoutOffersApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(CheckoutOffersApi.class, true, false).length > 0,
                "orders", "offers", "at.orders.offers.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Optional<String> inventorySku(String offerId) {
        if (offerId == null || offerId.isBlank()) {
            return Optional.empty();
        }
        return upstream.call(SERVICE, () -> api.getObject().inventorySku(offerId)).map(String::trim)
                .filter(sku -> !sku.isEmpty());
    }
}
