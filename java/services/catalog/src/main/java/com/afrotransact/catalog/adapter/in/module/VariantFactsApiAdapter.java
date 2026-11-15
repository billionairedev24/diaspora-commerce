package com.afrotransact.catalog.adapter.in.module;

import java.util.Optional;

import com.afrotransact.catalog.api.VariantFactsApi;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of the published {@link VariantFactsApi}: the anonymous public variant read
 * ({@code GET /v1/public/variants/{atin}}) without the HTTP round trip. Over HTTP it needs no scope, so it needs none here;
 * it still requires an in-process caller so that no code path reaches it unnamed.
 */
@Component
class VariantFactsApiAdapter implements VariantFactsApi {

    private final PublicCatalogService catalog;

    VariantFactsApiAdapter(PublicCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<VariantFacts> liveVariantFacts(String variantAtin) {
        InProcessCalls.require();
        try {
            PublicCatalogService.LiveVariant live = catalog.liveVariant(variantAtin);
            var product = live.product();
            return Optional.of(new VariantFacts(variantAtin, product.productTypeCode(), product.attributes(),
                    catalog.taxProfileOf(product).orElse(null), live.variant().tax().facts(),
                    product.details().sellerBehaviour(), product.owner().orgId()));
        } catch (NotFoundException ex) {
            return Optional.empty();
        }
    }
}
