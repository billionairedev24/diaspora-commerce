package com.afrotransact.ai.adapter.out.module;

import java.util.List;

import com.afrotransact.ai.port.OfferReader;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.offers.api.PublicOffersApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link OfferReader} through offers' published {@link PublicOffersApi} inside this JVM (mode {@code in-process}), entered as ai-gateway's
 * caller principal: the anonymous storefront read, no scope. No answer reads as no offers, so compare_offers degrades to the catalog
 * view, as over HTTP.
 */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "offers", value = ModuleMode.IN_PROCESS)
class InProcessOfferReader implements OfferReader {

    private static final String SERVICE = "offers";
    private static final String CURRENCY = "USD";

    private final ObjectProvider<PublicOffersApi> api;
    private final ModuleUpstream upstream;

    InProcessOfferReader(ObjectProvider<PublicOffersApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(PublicOffersApi.class, true, false).length > 0, "ai-gateway",
                SERVICE, "at.aigateway.offers.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public List<Offer> offers(String productAtin, Shopper shopper) {
        return upstream.call(SERVICE, () -> api.getObject().offersForProduct(productAtin, CURRENCY).stream()
                .map(o -> new Offer(o.offerId(), nullToEmpty(o.sellerName()), nullToEmpty(o.sellerKind()),
                        nullToEmpty(o.fulfilment()), o.priceMinor(), orElse(o.currency(), CURRENCY), o.inStock(),
                        o.deliveryDays(), orElse(o.condition(), "NEW"))).toList(), e -> List.of(), List::of);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String orElse(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }
}
