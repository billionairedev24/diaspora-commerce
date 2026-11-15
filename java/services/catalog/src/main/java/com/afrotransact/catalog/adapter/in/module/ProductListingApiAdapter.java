package com.afrotransact.catalog.adapter.in.module;

import java.util.Optional;

import com.afrotransact.catalog.api.ProductListingApi;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of the published {@link ProductListingApi}: the anonymous public variant read
 * ({@code GET /v1/public/variants/{atin}}) as checkout needs it. No scope over HTTP, none here; a caller principal is still
 * required so that no code path reaches it unnamed.
 */
@Component
class ProductListingApiAdapter implements ProductListingApi {

    private final PublicCatalogService catalog;

    ProductListingApiAdapter(PublicCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<LiveListing> liveListing(String variantAtin) {
        InProcessCalls.require();
        try {
            PublicCatalogService.LiveVariant live = catalog.liveVariant(variantAtin);
            var product = live.product();
            String image = product.images().isEmpty() ? null : product.images().get(0).url();
            return Optional.of(new LiveListing(variantAtin, product.atin().value(), product.title(), image,
                    product.owner().orgId(), product.owner().kind().name(), product.productTypeCode(),
                    product.attributes(), live.variant().priceMinor(), live.variant().currency(),
                    product.details().sellerBehaviour()));
        } catch (NotFoundException ex) {
            return Optional.empty();
        }
    }
}
