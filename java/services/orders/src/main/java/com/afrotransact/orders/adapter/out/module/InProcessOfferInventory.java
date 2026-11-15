package com.afrotransact.orders.adapter.out.module;

import java.util.Optional;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.orders.port.OfferInventory;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** {@link OfferInventory} through offers' {@link CheckoutOffersApi} inside this JVM (mode {@code in-process}). */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "offers", value = ModuleMode.IN_PROCESS)
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
        try {
            return upstream.call(SERVICE, () -> api.getObject().inventorySku(offerId)).map(String::trim)
                    .filter(sku -> !sku.isEmpty());
        } catch (UpstreamFailure f) {
            // 404 = unknown offer, as for the HTTP adapter.
            if (f.status() == 404) {
                return Optional.empty();
            }
            throw f;
        }
    }
}
